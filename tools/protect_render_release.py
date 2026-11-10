"""Create an opt-in protected Workshop review build from a verified render build."""
from pathlib import Path
import argparse
import hashlib
import hmac
import io
import json
import random
import secrets
import re
import shutil
import subprocess
import zipfile

from build import compile_java
from build_render_support import check_glsl_names, write
from build_eclipse_support import GF2_MANIFEST, RETIRED_ENTRIES
from common import BUILD, ROOT, environment, save, settings, sha


BRIDGE = 'aimoh/gf2/bridge/'
SEALED_RESOURCE = BRIDGE + 'render-inputs.bin'
MOTION_SUFFIX = '/shaders/dimensions/composite6.fsh'
PROBE_FRAGMENT_START = b'uniform vec3 uSceneOrigin;\nfloat authorSkyVisibility('
TOKEN = re.compile(r'^[ \t]*#[^\r\n]*|/\*[\s\S]*?\*/|//[^\r\n]*|"(?:\\.|[^"\\])*"|\.[ \t]*[A-Za-z_]\w*|\b[A-Za-z_]\w*\b', re.M | re.ASCII)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def renamed_shader(data, symbols, scope):
    original = data.decode('utf8')
    aliases = {name: 'q' + digest((scope + '/' + name).encode())[:10] for name in symbols}
    assert len(aliases) == len(symbols) == len(set(aliases.values())), scope
    assert not any(alias in original for alias in aliases.values()), scope

    def replace(source, names):
        return TOKEN.sub(lambda token: names.get(token[0], token[0]), source)

    changed = replace(original, aliases)
    for name, alias in aliases.items():
        assert alias in changed, f'Unmatched reviewed GLSL identifier in {scope}: {name}'
    assert replace(changed, {alias: name for name, alias in aliases.items()}) == original, scope
    encoded = changed.encode('utf8')
    check_glsl_names(scope, encoded)
    return encoded, aliases


COMMENTS = re.compile(rb'"(?:\\.|[^"\\])*"|/\*[\s\S]*?\*/|//[^\r\n]*')
ANNOTATION = re.compile(rb'RENDERTARGETS|DRAWBUFFERS|^//\s*\[|SPDX|copyright|license|Karis', re.I)


def strip_notes(data):
    def hide(match):
        comment = match[0]
        if comment.startswith(b'"') or ANNOTATION.search(comment):
            return comment
        # Preserve token separation and source line locations.
        return re.sub(rb'[^\r\n]', b' ', comment)

    return COMMENTS.sub(hide, data)


def protect_shaders(payload, backend, rules, fragments):
    changed = {}
    mapping = {}
    sealed = []
    relocated = {}

    def seal(entry, data):
        marker = digest(f'{backend}/{entry}'.encode())[:16]
        assert marker not in fragments and b'/*r116$' not in data, entry
        fragments[marker] = data
        sealed.append(entry)
        return f'/*r116${marker}*/'.encode()

    with zipfile.ZipFile(io.BytesIO(payload)) as source:
        if backend == 'native':
            for name, symbols in rules['native_local_symbols'].items():
                entry = 'shaders/gf2/' + name
                data = source.read(entry)
                original = (ROOT / 'render-support/native-pack' / entry).read_bytes()
                assert data.replace(b'\r\n', b'\n') == original.replace(b'\r\n', b'\n'), entry
                protected, mapping[entry] = renamed_shader(data, symbols, entry)
                changed[entry] = seal(entry, strip_notes(protected))
        else:
            entries = [name for name in source.namelist() if name.endswith(MOTION_SUFFIX)]
            assert len(entries) == 1, entries
            entry = entries[0]
            data = source.read(entry)
            fragment = (ROOT / 'render-support/eclipse/shaders/actor-motion.glsl').read_bytes().replace(b'\r\n', b'\n')
            assert data.count(fragment) == 1, 'Eclipse author-owned motion fragment differs from the built package'
            protected, mapping[entry] = renamed_shader(fragment, rules['eclipse_motion_local_symbols'], entry)
            marker = seal(entry, strip_notes(protected))
            changed[entry] = data.replace(fragment, marker, 1)
            assert changed[entry].replace(marker, fragment, 1) == data
            shader_root = entry.removesuffix('dimensions/composite6.fsh')
            bootstrap = json.loads(source.read(GF2_MANIFEST))
            probe = bootstrap['native_probe']
            indexed = {row['path']: row for row in [probe, *bootstrap['kernels'].values()]}

            def seal_kernel(entry, prefix, fragment, original):
                row = indexed[entry.removeprefix(shader_root)]
                assert len(original) == row['bytes'] and digest(original) == row['sha256'], entry
                code = prefix + fragment
                fingerprint = digest(code)
                path = 'gf2/data/' + fingerprint + '.comp'
                target = shader_root + path
                assert target == entry or target not in source.namelist()
                row.update(path=path, sha256=fingerprint, bytes=len(code))
                relocated[entry] = target
                changed[entry] = prefix + seal(target, fragment)

            for name in rules['eclipse_owned_files']:
                owned = shader_root + name
                code = source.read(owned)
                if name.startswith('gf2/data/'):
                    assert digest(code) == Path(name).stem, owned
                    seal_kernel(owned, b'', strip_notes(code), code)
                else:
                    changed[owned] = seal(owned, strip_notes(code))

            entry = shader_root + probe['path']
            data = source.read(entry)
            assert data.count(PROBE_FRAGMENT_START) == 1, entry
            split = data.index(PROBE_FRAGMENT_START)
            author_fragment = data[split:]
            assert digest(author_fragment) == rules['eclipse_probe_fragment_sha256'], entry
            seal_kernel(entry, data[:split], strip_notes(author_fragment), data)

        manifest = [name for name in source.namelist() if name.endswith('shaders/gf2/manifest.json')]
        assert len(manifest) == 1, manifest
        entry = manifest[0]
        data = source.read(entry)
        if backend == 'eclipse':
            data = (json.dumps(bootstrap, indent=2) + '\n').encode()
        else:
            bootstrap = json.loads(data)
        public = {key: bootstrap[key] for key in ('format', 'pipeline', 'addon_runtime', 'character_catalog')}
        public['input'] = seal(entry, data).decode()
        changed[entry] = (json.dumps(public, separators=(',', ':')) + '\n').encode()
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w') as target:
            for name in source.namelist():
                if not name.endswith('/'):
                    write(target, relocated.get(name, name), changed[name] if name in changed else source.read(name))
        with zipfile.ZipFile(output) as target:
            assert target.testzip() is None
            assert all(target.read(name) == source.read(name) for name in source.namelist()
                       if name not in changed and not name.endswith('/'))
    return output.getvalue(), mapping, sealed


def owned_classes(archive):
    historical = subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', '3127265^', 'render-support'],
                                         cwd=ROOT, encoding='utf8').splitlines()
    sources = {file.stem for file in (ROOT / 'render-support').rglob('*.java')}
    sources.update(Path(file).stem for file in historical if file.endswith('.java'))
    classes = set()
    assert not RETIRED_ENTRIES.intersection(archive.namelist()), 'Rebuild cleaned Eclipse support'
    for name in archive.namelist():
        if not name.endswith('.class'):
            continue
        root = Path(name).name.split('$', 1)[0].removesuffix('.class')
        source = root.replace('AimohEclipseGf2', 'AimohGf2')
        effect = name.startswith(('viewpoint/render/Aimoh', 'viewpoint/platform/Aimoh'))
        hook = name.startswith(BRIDGE) and root in {'NativeHooks', 'RuntimeHooks', 'EclipseHooks', 'AimohSealedSources', 'UnifiedStartup'}
        if effect or hook:
            assert source in sources or root == 'EclipseHooks', name
            classes.add(name)
    return classes


def run_java(command, log, input_data=None):
    result = subprocess.run(command, env=environment(), input=input_data, capture_output=True, timeout=120)
    output = (result.stdout + result.stderr).decode('utf8', errors='replace')
    log.write_text(output, 'utf8')
    if result.returncode:
        print(output[-5000:], flush=True)
        result.check_returncode()


def protect_classes(source, private, rules, proguard_zip, config):
    assert sha(proguard_zip) == rules['proguard_zip_sha256'], 'Unverified ProGuard distribution'
    with zipfile.ZipFile(proguard_zip) as release:
        entry = f"proguard-{rules['proguard_version']}/lib/proguard.jar"
        proguard = private / 'proguard.jar'
        proguard.write_bytes(release.read(entry))
    owned = owned_classes(source)
    originals = {name for name in source.namelist() if name.endswith('.class')}
    keep = originals - owned
    internal = set(rules['internal_java_roots'])
    renamable = {name for name in owned if Path(name).name.split('$', 1)[0].removesuffix('.class') in internal}
    assert internal <= {Path(name).name.split('$', 1)[0].removesuffix('.class') for name in owned}
    assert not any(name.removesuffix('.class').encode() in source.read(other) for name in renamable for other in keep), 'A retained class refers to an internal rename target'
    files = {'owned.jar': owned, 'other.jar': keep}
    for jar, names in files.items():
        with zipfile.ZipFile(private / jar, 'w') as archive:
            for name in sorted(names):
                write(archive, name, source.read(name))

    game = Path(config['game_root'])
    java = game / 'jre64/bin/java.exe'
    runtime = private / 'java-runtime.jar'
    classes = private / 'jrt-classes'
    compile_java(config, [ROOT / 'tools/java/ProtectionRuntimeClasses.java'], classes, [])
    run_java([str(java), '-cp', str(classes), 'ProtectionRuntimeClasses', str(runtime)], private / 'java-runtime.log')

    output = private / 'owned-protected.jar'
    libraries = [runtime, private / 'other.jar', Path(config['viewpoint_jar']), Path(config['reference_jar']),
                 Path(config['zombiebuddy_jar']), game / 'projectzomboid.jar']
    mapping = private / 'JAVA-MAPPING.txt'
    options = [f"-injars '{(private / 'owned.jar').as_posix()}'", f"-outjars '{output.as_posix()}'",
               *(f"-libraryjars '{path.as_posix()}'" for path in libraries),
               '-dontshrink', '-dontoptimize', '-dontskipnonpubliclibraryclasses', '-dontskipnonpubliclibraryclassmembers',
               '-keeppackagenames **', '-keepclassmembers class ** { !private <fields>; !private <methods>; }',
               '-keepclassmembers class viewpoint.render.AimohGf2DepthStencil { *** copy; }',
               '-keepattributes Exceptions,InnerClasses,EnclosingMethod,Signature,*Annotation*,Record,NestHost,NestMembers,PermittedSubclasses',
               *(f"-keepnames class {name.removesuffix('.class').replace('/', '.')}" for name in sorted(owned - renamable)),
               f"-printmapping '{mapping.as_posix()}'"]
    pro = private / 'protection.pro'
    pro.write_text('\n'.join(options) + '\n', 'utf8')
    run_java([str(java), '-jar', str(proguard), '@' + str(pro)], private / 'proguard.log')

    names = {}
    renamed_members = 0
    for line in mapping.read_text('utf8').splitlines():
        if not line.startswith(' ') and line.endswith(':') and ' -> ' in line:
            original, new = line.removesuffix(':').split(' -> ', 1)
            names[original.replace('.', '/') + '.class'] = new.replace('.', '/') + '.class'
        elif line.startswith('    ') and ' -> ' in line:
            original, new = line.rsplit(' -> ', 1)
            renamed_members += original.rsplit(' ', 1)[-1].split('(')[0] != new
    assert set(names) == owned and all(names[n] == n for n in owned - renamable)
    assert not set(names.values()) & keep and len(set(names.values())) == len(owned)
    assert all(names[n].rsplit('/', 1)[0] == n.rsplit('/', 1)[0] for n in owned)
    assert any(names[n] != n for n in renamable) and renamed_members
    return output, names, renamed_members


def release_context(seed, raw, owned, names, private, config):
    def part(label):
        return hmac.digest(seed, label.encode(), 'sha256')

    layout = random.Random(part('layout'))
    carrier_names = ['x' + part(f'class/{i}')[:4].hex() for i in range(4)]
    assert len(set(carrier_names)) == len(carrier_names)
    carriers = {name: [] for name in carrier_names}
    order = carrier_names * 2
    layout.shuffle(order)
    calls = []
    for i, owner in enumerate(order):
        method = 'a' + part(f'method/{i}')[:4].hex()
        values = part(f'fragment/{i}')[:16]
        carriers[owner].append((method, values))
        calls.append((owner, method, values))
    layout.shuffle(calls)
    layout.shuffle(carrier_names)

    generated = private / 'context-sources'
    generated.mkdir()
    sources = []
    for name, methods in carriers.items():
        layout.shuffle(methods)
        lines = ['package aimoh.gf2.bridge;', f'final class {name} {{']
        for method, values in methods:
            signed = ', '.join(str(v if v < 128 else v - 256) for v in values)
            lines.append(f'    static void {method}(java.security.MessageDigest d) {{ d.update(new byte[] {{{signed}}}); }}')
        file = generated / f'{name}.java'
        file.write_text('\n'.join([*lines, '}']) + '\n', 'utf8')
        sources.append(file)

    classes = ', '.join(f'{name}.class' for name in ['K', *carrier_names])
    lines = ['package aimoh.gf2.bridge;', 'final class K {',
             f'    static Class<?>[] types() {{ return new Class<?>[] {{{classes}}}; }}',
             '    static void mix(java.security.MessageDigest d) {']
    lines.extend(f'        {owner}.{method}(d);' for owner, method, _ in calls)
    dispatcher = generated / 'K.java'
    dispatcher.write_text('\n'.join([*lines, '    }', '}']) + '\n', 'utf8')
    output = private / 'context-classes'
    compile_java(config, [dispatcher, *sources], output, [raw])
    rows = {BRIDGE + name + '.class': (output / BRIDGE / (name + '.class')).read_bytes()
            for name in ['K', *carrier_names]}

    key = hashlib.sha256()
    with zipfile.ZipFile(owned) as archive:
        for tail in ('', '$Payload', '$Watch'):
            entry = names[BRIDGE + 'AimohSealedSources' + tail + '.class']
            key.update(hashlib.sha256(archive.read(entry)).digest())
    for data in rows.values():
        key.update(hashlib.sha256(data).digest())
    for _, _, values in calls:
        key.update(values)
    return rows, key.digest()


def package(output, proguard, metadata, seed_from=None):
    config = settings()
    rules_file = ROOT / 'render-support/release-protection.json'
    rules = json.loads(rules_file.read_text('utf8'))
    report = json.loads((BUILD / 'combined-support/build.json').read_text('utf8'))
    raw = Path(report['jar'])
    assert report['backend'] == 'combined' and sha(raw) == report['jar_sha256']
    for name, expected in report['source_hashes'].items():
        assert sha(ROOT / name) == expected, f'Rebuild changed source: {name}'
    host = Path(config['viewpoint_jar'])
    inputs = {path: sha(path) for path in (raw, host, Path(config['reference_jar']))}
    signature=host.with_suffix('.jar.zbs')
    if signature.is_file():inputs[signature]=sha(signature)
    assert inputs[host] == report['host_sha256']
    output = output.resolve()
    assert output.is_relative_to(BUILD.resolve()) and output != BUILD.resolve(), 'Protected outputs belong under build/'
    output.mkdir(parents=True, exist_ok=False)
    private = output / 'private'
    private.mkdir()
    seed = seed_from.read_bytes() if seed_from else secrets.token_bytes(32)
    assert len(seed) == 32, 'Expected a 32-byte private build seed'
    (private / 'seed.bin').write_bytes(seed)

    origin = json.loads((ROOT / 'provenance/render-support.json').read_text('utf8'))
    workshop_id = origin['workshop_id']
    assert f'id={workshop_id}' in (metadata / 'workshop.txt').read_text('utf-8-sig').splitlines()
    assert f"modversion={report['version']}" in (ROOT / 'render-support/mod/42/mod.info').read_text('utf-8-sig').splitlines()
    project = output / 'Workshop/R116RenderSupport'
    mod = project / 'Contents/mods/AimohGF2Eclipse'
    shutil.copytree(ROOT / 'render-support/mod', mod)
    (mod / 'common/.gitkeep').unlink(missing_ok=True)
    for name in ('workshop.txt', 'preview.png'):
        shutil.copy2(metadata / name, project / name)
    jar = mod / '42/media/java/client/AimohGF2Eclipse.jar'
    jar.parent.mkdir(parents=True, exist_ok=True)
    shader_report = {}
    shader_mapping = {}
    with zipfile.ZipFile(raw) as source:
        protected, names, renamed_members = protect_classes(source, private, rules, proguard, config)
        shaders = {}
        fragments = {}
        for backend, row in report['backends'].items():
            entry = row['shader_payload']
            data = source.read(entry)
            assert digest(data) == row['shader_sha256']
            shaders[entry], shader_mapping[backend], sealed = protect_shaders(data, backend, rules, fragments)
            shader_report[backend] = {'sha256': digest(shaders[entry]), 'sealed_entries': sorted(sealed)}
        source_archive = private / 'render-inputs.zip'
        with zipfile.ZipFile(source_archive, 'w') as fragment_archive:
            for name, data in sorted(fragments.items()):
                write(fragment_archive, name, data)
        context, key = release_context(seed, raw, protected, names, private, config)
        nonce = hmac.digest(seed, b'nonce/' + hashlib.sha256(source_archive.read_bytes()).digest(), 'sha256')[:12]
        tool = ROOT / 'tools/java/SealRenderResources.java'
        tool_classes = private / 'sealer-classes'
        compile_java(config, [tool], tool_classes, [])
        sealed_resource = private / 'render-inputs.bin'
        java = Path(config['game_root']) / 'jre64/bin/java.exe'
        run_java([str(java), '-cp', str(tool_classes), 'aimoh.gf2.bridge.SealRenderResources',
                  str(source_archive), str(sealed_resource)], private / 'sealer.log', key + nonce)
        with zipfile.ZipFile(protected) as owned, zipfile.ZipFile(jar, 'w') as target:
            assert {n for n in owned.namelist() if n.endswith('.class')} == set(names.values())
            for name in source.namelist():
                if name.endswith('/'):
                    continue
                if name in context:
                    continue
                if name in names:
                    write(target, names[name], owned.read(names[name]))
                elif name in shaders:
                    write(target, name, shaders[name])
                else:
                    write(target, name, source.read(name))
            assert set(context) & set(source.namelist()) == {BRIDGE + 'K.class'}
            assert not set(context) & set(names.values())
            for name, data in sorted(context.items()):
                write(target, name, data)
            assert SEALED_RESOURCE not in source.namelist()
            write(target, SEALED_RESOURCE, sealed_resource.read_bytes())
        with zipfile.ZipFile(jar) as result:
            assert result.testzip() is None and all(result.read(name) == source.read(name) for name in result.namelist() if name not in names.values() and name not in shaders and name not in context and name != SEALED_RESOURCE)
    assert all(sha(path) == value for path, value in inputs.items())
    save(private / 'GLSL-MAPPING.json', shader_mapping)
    save(output / 'protection-report.json', dict(status='PROTECTED_REVIEW_BUILD', version=report['version'],
         input_jar_sha256=inputs[raw], jar_sha256=sha(jar), host_sha256=inputs[host], source_commit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, encoding='utf8').strip(),
         protection_rules_sha256=sha(rules_file), protection_script_sha256=sha(Path(__file__)),
         proguard_version=rules['proguard_version'], proguard_zip_sha256=rules['proguard_zip_sha256'],
         protected_classes=len(names), renamed_classes=sum(a != b for a, b in names.items()), renamed_java_members=renamed_members,
         shaders=shader_report, sealed_input_count=len(fragments), sealed_payload_sha256=sha(sealed_resource),
         seal_tool_sha256=sha(tool), generated_context_classes=len(context), randomized_context=True, private_mapping_bundled=False, installed=False, uploaded=False))
    print(f'Protected review JAR: {jar}', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--proguard-zip', type=Path, required=True)
    parser.add_argument('--metadata-from', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=BUILD / 'protected-render-review')
    parser.add_argument('--seed-from', type=Path, help='Private seed for reproducing an exact prior build')
    args = parser.parse_args()
    package(args.output, args.proguard_zip.resolve(strict=True), args.metadata_from.resolve(strict=True), args.seed_from)
