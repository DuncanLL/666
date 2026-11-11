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
import struct
import subprocess
import zipfile

from build import compile_java
from build_render_support import check_glsl_names, write
from build_eclipse_support import GF2_MANIFEST, RETIRED_ENTRIES
from common import BUILD, ROOT, environment, save, settings, sha


BRIDGE = 'aimoh/gf2/bridge/'
SEALED_RESOURCE = BRIDGE + 'render-inputs.bin'
LOADER = BRIDGE + 'AimohSealedSources'
LOADER_PARTS = ('', '$Payload', '$Watch')
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


def seal_names(seed):
    return BRIDGE + hmac.digest(seed, b'archive-entry', 'sha256')[:12].hex()


def protect_shaders(payload, backend, rules, fragments):
    import basic_shader_adapter as basic
    changed = {}
    mapping = {}
    sealed = []
    relocated = {}
    public_kernels = {}

    def with_public_source(entry, formal, public):
        public = public.replace(b'\r', b'').rstrip() + b'\n'
        name = entry.rsplit('shaders/', 1)[1]
        key = name + '/' + digest(public)
        assert key not in fragments, entry
        fragments[key] = formal
        sealed.append(entry)
        return public

    with zipfile.ZipFile(io.BytesIO(payload)) as source:
        if backend == 'native':
            for name, symbols in rules['native_local_symbols'].items():
                entry = 'shaders/gf2/' + name
                data = source.read(entry)
                original = (ROOT / 'render-support/native-pack' / entry).read_bytes()
                assert data.replace(b'\r\n', b'\n') == original.replace(b'\r\n', b'\n'), entry
                protected, mapping[entry] = renamed_shader(data, symbols, entry)
                changed[entry] = with_public_source(entry, strip_notes(protected), basic.native_shader(name, data))
        else:
            entries = [name for name in source.namelist() if name.endswith(MOTION_SUFFIX)]
            assert len(entries) == 1, entries
            entry = entries[0]
            data = source.read(entry)
            fragment = (ROOT / 'render-support/eclipse/shaders/actor-motion.glsl').read_bytes().replace(b'\r\n', b'\n')
            assert data.count(fragment) == 1, 'Eclipse author-owned motion fragment differs from the built package'
            protected, mapping[entry] = renamed_shader(fragment, rules['eclipse_motion_local_symbols'], entry)
            changed[entry] = with_public_source(entry, data.replace(fragment, strip_notes(protected), 1),
                                                data.replace(fragment, basic.ECLIPSE_COMPOSITE, 1))
            shader_root = entry.removesuffix('dimensions/composite6.fsh')
            bootstrap = json.loads(source.read(GF2_MANIFEST))
            probe = bootstrap['native_probe']
            indexed = {row['path']: row for row in [probe, *bootstrap['kernels'].values()]}

            def seal_kernel(entry, prefix, fragment, original, public):
                row = indexed[entry.removeprefix(shader_root)]
                assert len(original) == row['bytes'] and digest(original) == row['sha256'], entry
                code = prefix + fragment
                fingerprint = digest(code)
                path = 'gf2/data/' + fingerprint + '.comp'
                target = shader_root + path
                assert target == entry or target not in source.namelist()
                row.update(path=path, sha256=fingerprint, bytes=len(code))
                relocated[entry] = target
                changed[entry] = with_public_source(target, code, public)
                public_kernels[path] = changed[entry]

            for name in rules['eclipse_owned_files']:
                owned = shader_root + name
                code = source.read(owned)
                if name.startswith('gf2/data/'):
                    assert digest(code) == Path(name).stem, owned
                    seal_kernel(owned, b'', strip_notes(code), code, basic.eclipse_shader(owned, code, bootstrap['kernels']))
                else:
                    changed[owned] = with_public_source(owned, strip_notes(code), basic.eclipse_shader(owned, code, bootstrap['kernels']))

            entry = shader_root + probe['path']
            data = source.read(entry)
            assert data.count(PROBE_FRAGMENT_START) == 1, entry
            split = data.index(PROBE_FRAGMENT_START)
            author_fragment = data[split:]
            assert digest(author_fragment) == rules['eclipse_probe_fragment_sha256'], entry
            seal_kernel(entry, data[:split], strip_notes(author_fragment), data, basic.eclipse_probe(data[:split]))

        manifest = [name for name in source.namelist() if name.endswith('shaders/gf2/manifest.json')]
        assert len(manifest) == 1, manifest
        entry = manifest[0]
        data = source.read(entry)
        if backend == 'eclipse':
            data = (json.dumps(bootstrap, indent=2) + '\n').encode()
        else:
            bootstrap = json.loads(data)
        public = basic.public_manifest(json.loads(data))
        if backend == 'eclipse':
            for row in [public['native_probe'], *public['kernels'].values()]:
                source_bytes = public_kernels[row['path']]
                row.update(sha256=digest(source_bytes), bytes=len(source_bytes))
        changed[entry] = with_public_source(entry, data, (json.dumps(public, indent=2) + '\n').encode())
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


def release_context(seed, loader_name, private, config):
    def part(label):
        return hmac.digest(seed, label.encode(), 'sha256')

    layout = random.Random(part('layout'))
    resource = seal_names(seed)
    dispatcher_name = 'z' + part('dispatcher')[:5].hex()
    carrier_names = ['x' + part(f'class/{i}')[:4].hex() for i in range(4)]
    assert len(set(carrier_names)) == len(carrier_names)
    carriers = {name: [] for name in carrier_names}
    order = carrier_names * 2
    layout.shuffle(order)
    calls = []
    for i, owner in enumerate(order):
        method = 'a' + part(f'method/{i}')[:4].hex()
        values = part(f'fragment/{i}')[:16]
        calls.append((owner, method, values))
    layout.shuffle(calls)
    layout.shuffle(carrier_names)

    # The next dispatch label depends on the decoded words of the current row.
    # Packager and generated loader perform the same unsigned 32-bit arithmetic.
    mask = 0xffffffff
    labels = layout.sample(range(1, 2**31), len(calls) + 1)
    initial = state = layout.getrandbits(32)
    salt = layout.getrandbits(32)
    shift = layout.randrange(3, 30)
    for index, (owner, method, values) in enumerate(calls):
        row = []
        for word in struct.unpack('<4I', values):
            row.append(word ^ state)
            combined = (state + word) & mask
            state = (((combined << shift) | (combined >> (32 - shift))) & mask) ^ salt
        row.append(labels[index + 1] ^ state)
        carriers[owner].append((method, row))

    generated = private / 'context-sources'
    generated.mkdir()
    sources = []
    for name, methods in carriers.items():
        layout.shuffle(methods)
        lines = ['package aimoh.gf2.bridge;', f'final class {name} {{']
        for method, row in methods:
            words = ', '.join(hex(word) for word in row)
            lines.append(f'    static int[] {method}() {{ return new int[] {{{words}}}; }}')
        file = generated / f'{name}.java'
        file.write_text('\n'.join([*lines, '}']) + '\n', 'utf8')
        sources.append(file)

    classes = ', '.join(f'{name}.class' for name in [dispatcher_name, *carrier_names])
    cases = [f'                case {label} -> {owner}.{method}();'
             for label, (owner, method, _) in zip(labels, calls)]
    layout.shuffle(cases)
    case_lines = '\n'.join(cases)
    dispatcher = generated / f'{dispatcher_name}.java'
    dispatcher.write_text(f'''package aimoh.gf2.bridge;
final class {dispatcher_name} {{
    static Class<?>[] a() {{ return new Class<?>[] {{{classes}}}; }}
    static void b(java.security.MessageDigest digest) {{
        int state = {hex(initial)}, slot = {labels[0]};
        for (int remaining = {len(calls)}; remaining > 0; remaining--) {{
            int[] row = switch (slot) {{
{case_lines}
                default -> throw new IllegalStateException();
            }};
            for (int i = 0; i < 4; i++) {{
                int word = row[i] ^ state;
                for (int bit = 0; bit < 32; bit += 8) digest.update((byte)(word >>> bit));
                state = Integer.rotateLeft(state + word, {shift}) ^ {hex(salt)};
            }}
            slot = row[4] ^ state;
        }}
        if (slot != {labels[-1]}) throw new IllegalStateException();
    }}
    static String c(int mask, int[] words) {{
        char[] text = new char[words.length];
        for (int i = 0; i < words.length; i++) text[i] = (char)(words[i] ^ Integer.rotateLeft(mask, i));
        return new String(text);
    }}
}}
''', 'utf8')

    def hidden(text, label):
        word = int.from_bytes(part(label)[:4], 'big')
        bits = f'{word:032b}'
        encoded = [hex(ord(char) ^ int(bits[i % 32:] + bits[:i % 32], 2)) for i, char in enumerate(text)]
        return f'{dispatcher_name}.c({hex(word)}, new int[] {{{", ".join(encoded)}}})'

    template = ROOT / 'render-support/java' / (LOADER + '.java')
    loader = template.read_text('utf8')
    replacements = {
        '"/' + SEALED_RESOURCE + '"': hidden('/' + resource, 'resource-text'),
        '"SHA-256"': hidden('SHA-256', 'digest-text'),
        '"AES/GCM/NoPadding"': hidden('AES/GCM/NoPadding', 'cipher-text'),
        '"AES"': hidden('AES', 'key-type-text'),
        'K.types()': dispatcher_name + '.a()',
        'K.mix(context)': dispatcher_name + '.b(context)',
    }
    for old, new in replacements.items():
        assert old in loader, old
        loader = loader.replace(old, new)
    loader = loader.replace('AimohSealedSources', loader_name)
    loader_file = generated / f'{loader_name}.java'
    loader_file.write_text(loader, 'utf8')

    output = private / 'context-classes'
    compile_java(config, [loader_file, dispatcher, *sources], output, [])
    order = [*(loader_name + part_name for part_name in LOADER_PARTS), dispatcher_name, *carrier_names]
    rows = {BRIDGE + name + '.class': (output / BRIDGE / (name + '.class')).read_bytes() for name in order}

    key = hashlib.sha256()
    for data in rows.values():
        key.update(hashlib.sha256(data).digest())
    for _, _, values in calls:
        key.update(values)
    return rows, key.digest()


def seal_archive(seed, loader_name, fragments, private, config):
    source_archive = private / 'render-inputs.zip'
    with zipfile.ZipFile(source_archive, 'w') as archive:
        for name, data in sorted(fragments.items()):
            write(archive, name, data)
    nonce = hmac.digest(seed, b'nonce/' + hashlib.sha256(source_archive.read_bytes()).digest(), 'sha256')[:12]
    tool = ROOT / 'tools/java/SealRenderResources.java'
    tool_classes = private / 'sealer-classes'
    compile_java(config, [tool], tool_classes, [])
    context, key = release_context(seed, loader_name, private, config)
    resource = seal_names(seed)
    sealed = private / Path(resource).name
    java = Path(config['game_root']) / 'jre64/bin/java.exe'
    run_java([str(java), '-cp', str(tool_classes), 'aimoh.gf2.bridge.SealRenderResources',
              str(source_archive), str(sealed)], private / 'sealer.log', key + nonce)
    return context, resource, sealed.read_bytes()


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
        loader = Path(names[LOADER + '.class']).stem
        context, resource, sealed_resource = seal_archive(seed, loader, fragments, private, config)
        replaced = {LOADER + part + '.class' for part in LOADER_PARTS} | {BRIDGE + 'K.class'}
        added = set(context) | {resource}
        assert not added & ((set(source.namelist()) - replaced) | (set(names.values()) - {names[n] for n in replaced if n in names}))
        with zipfile.ZipFile(protected) as owned, zipfile.ZipFile(jar, 'w') as target:
            assert {n for n in owned.namelist() if n.endswith('.class')} == set(names.values())
            for name in source.namelist():
                if name.endswith('/'):
                    continue
                if name in replaced:
                    continue
                if name in names:
                    write(target, names[name], owned.read(names[name]))
                elif name in shaders:
                    write(target, name, shaders[name])
                else:
                    write(target, name, source.read(name))
            for name, data in sorted(context.items()):
                write(target, name, data)
            write(target, resource, sealed_resource)
        with zipfile.ZipFile(jar) as result:
            unchanged = set(result.namelist()) - set(names.values()) - set(shaders) - added
            assert result.testzip() is None and all(result.read(name) == source.read(name) for name in unchanged)
    assert all(sha(path) == value for path, value in inputs.items())
    save(private / 'GLSL-MAPPING.json', shader_mapping)
    save(output / 'protection-report.json', dict(status='PROTECTED_REVIEW_BUILD', version=report['version'],
         input_jar_sha256=inputs[raw], jar_sha256=sha(jar), host_sha256=inputs[host], source_commit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, encoding='utf8').strip(),
         protection_rules_sha256=sha(rules_file), protection_script_sha256=sha(Path(__file__)),
         proguard_version=rules['proguard_version'], proguard_zip_sha256=rules['proguard_zip_sha256'],
         protected_classes=len(names), renamed_classes=sum(a != b for a, b in names.items()), renamed_java_members=renamed_members,
         shaders=shader_report, sealed_input_count=len(fragments), sealed_payload_sha256=digest(sealed_resource),
         sealed_resource=resource, public_source_view=True, loader_class=loader,
         dispatcher_class=Path(list(context)[len(LOADER_PARTS)]).stem,
         seal_tool_sha256=sha(ROOT / 'tools/java/SealRenderResources.java'), generated_context_classes=len(context),
         randomized_context=True, private_mapping_bundled=False, installed=False, uploaded=False))
    print(f'Protected review JAR: {jar}', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--proguard-zip', type=Path, required=True)
    parser.add_argument('--metadata-from', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=BUILD / 'protected-render-review')
    parser.add_argument('--seed-from', type=Path, help='Private seed for reproducing an exact prior build')
    args = parser.parse_args()
    package(args.output, args.proguard_zip.resolve(strict=True), args.metadata_from.resolve(strict=True), args.seed_from)
