"""Compile the complete game runtime from the consolidated source tree."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import zipfile
from common import ROOT, BUILD, settings, sha, save, environment


def compile_java(config, sources, target, classpath):
    target.mkdir(parents=True, exist_ok=True)
    command = [str(Path(config['game_root']) / 'jre64/bin/java.exe'), '-jar', config['ecj_jar'],
               '-25', '-encoding', 'UTF-8', '-classpath', ';'.join(map(str, classpath)),
               '-d', str(target), *map(str, sources)]
    result = subprocess.run(command, env=environment(), capture_output=True, text=True, encoding='utf8')
    (target.parent / (target.name + '.log')).write_text(result.stdout + result.stderr, 'utf8')
    if result.returncode:
        print(result.stdout + result.stderr)
        result.check_returncode()


def build(compare_baseline=False,native_candidate=None,release_reference=None):
    config = settings()
    game = Path(config['game_root'])
    classpath = [config['viewpoint_jar'], config['zombiebuddy_jar'], *game.glob('*.jar')]
    source = sorted((ROOT / 'game/java').rglob('*.java'))
    classes = BUILD / ('release-classes' if release_reference is not None else 'classes')
    compile_java(config, source, classes, classpath)
    compile_java(config, [ROOT / 'tools/java/CompareRuntime.java'], BUILD / 'tools', [config['zombiebuddy_jar']])
    if compare_baseline or release_reference is not None:
        reference=Path(config['reference_jar']) if release_reference is None else release_reference.resolve(strict=True)
        if release_reference is not None:
            release=json.loads((ROOT/'provenance/character-release.json').read_text('utf8'))
            assert sha(reference)==release['runtime_sha256'], 'Character release runtime fingerprint differs'
        comparison = subprocess.run([str(game / 'jre64/bin/java.exe'), '-cp', str(BUILD / 'tools') + ';' + config['zombiebuddy_jar'],
                                     'CompareRuntime', str(reference), str(classes)],
                                    env=environment(), capture_output=True, text=True, encoding='utf8')
        (BUILD / 'class-comparison.txt').write_text(comparison.stdout + comparison.stderr, 'utf8')
        print(comparison.stdout, flush=True)
        comparison.check_returncode()
    if release_reference is not None:
        # Keep the tested runtime bytes after verifying that the compiled source matches it.
        with zipfile.ZipFile(reference) as archive:
            assert archive.testzip() is None
            prefix='aimoh/pmxpreview/native/windows-x64/'
            for name,digest in release['native_hashes'].items():
                assert hashlib.sha256(archive.read(prefix+name)).hexdigest()==digest,name
        jar=BUILD/'AimohPMXPreview.jar'
        temporary=jar.with_suffix('.jar.writing')
        shutil.copyfile(reference,temporary)
        os.replace(temporary,jar)
        save(BUILD/'build.json',dict(status='PASS_CHARACTER_RELEASE_SOURCE_PARITY',jar=str(jar),
            jar_sha256=sha(jar),native_version=release['native_version'],release_reference=True,
            source_hashes={p.relative_to(ROOT).as_posix():sha(p) for p in source},
            native_rebuilt=False,game_started=False,installed=False))
        print('Verified character release: '+str(jar),flush=True)
        return
    baseline = json.loads((ROOT / 'provenance/baseline.json').read_text('utf8'))
    candidate=None if native_candidate is None else json.loads(Path(native_candidate).read_text('utf8'))
    natives = {name: Path(config['native_source']) / name for name in baseline['native_hashes']} if candidate is None else {name:Path(row['path'])for name,row in candidate['files'].items()}
    for name, path in natives.items():
        assert sha(path) == (baseline['native_hashes'][name] if candidate is None else candidate['files'][name]['sha256']), 'Native input changed: ' + name
    resources={}
    if candidate is not None:
        from prepare_chest_data import prepare
        resources=prepare(config)
    hashes = {name: sha(path) for name, path in natives.items()}
    identity = hashlib.sha256(json.dumps(hashes, sort_keys=True).encode()).hexdigest()
    index = 'bundle=' + identity + '\nfiles=' + ','.join(sorted(natives)) + '\n'
    index += ''.join(name + '=' + hashes[name] + '\n' for name in sorted(natives))
    jar = BUILD / 'AimohPMXPreview.jar'
    temporary = jar.with_suffix('.jar.writing')
    with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED) as archive:
        def write(name, data):
            # Build timestamps must not change the identity of unchanged runtime inputs.
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, data)
        for path in sorted(classes.rglob('*.class')): write(path.relative_to(classes).as_posix(), path.read_bytes())
        prefix = 'aimoh/pmxpreview/native/windows-x64/'
        write(prefix + 'index.properties', index)
        for name in sorted(natives): write(prefix + name, natives[name].read_bytes())
        for name,path in sorted(resources.items()):write(name,path.read_bytes())
    with zipfile.ZipFile(temporary) as archive: assert archive.testzip() is None
    os.replace(temporary, jar)
    save(BUILD / 'build.json', {'status': 'PASS_FULL_SOURCE_BUILD_AND_RUNTIME_CLASS_PARITY' if compare_baseline else 'PASS_FULL_SOURCE_BUILD',
         'jar': str(jar), 'jar_sha256': sha(jar), 'jar_bytes': jar.stat().st_size,
         'reference_jar_sha256': sha(config['reference_jar']), 'java_sources': len(source),
         'native_hashes': hashes, 'native_rebuilt': candidate is not None, 'native_version':baseline['native_version'] if candidate is None else candidate['native_version'],
         'runtime_class_baseline_compared': compare_baseline,'chest_models':len(resources),
         'source_hashes': {p.relative_to(ROOT).as_posix(): sha(p) for p in source},
         'game_started': False, 'installed': False})
    print('Built: ' + str(jar), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compare-baseline', action='store_true', help='Require exact runtime class parity, ignoring compiler debug attributes.')
    native_inputs=parser.add_mutually_exclusive_group()
    native_inputs.add_argument('--native-candidate',type=Path)
    native_inputs.add_argument('--release-reference',type=Path,
                               help='Verify Java source parity and reuse the pinned release runtime verbatim.')
    args=parser.parse_args();build(args.compare_baseline,args.native_candidate,args.release_reference)
