"""Compile the complete game runtime from the consolidated source tree."""
from pathlib import Path
import argparse
import hashlib
import json
import os
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


def build(compare_baseline=False):
    config = settings()
    game = Path(config['game_root'])
    classpath = [config['viewpoint_jar'], config['zombiebuddy_jar'], *game.glob('*.jar')]
    source = sorted((ROOT / 'game/java').rglob('*.java'))
    classes = BUILD / 'classes'
    compile_java(config, source, classes, classpath)
    compile_java(config, [ROOT / 'tools/java/CompareRuntime.java'], BUILD / 'tools', [config['zombiebuddy_jar']])
    if compare_baseline:
        comparison = subprocess.run([str(game / 'jre64/bin/java.exe'), '-cp', str(BUILD / 'tools') + ';' + config['zombiebuddy_jar'],
                                     'CompareRuntime', config['reference_jar'], str(classes)],
                                    env=environment(), capture_output=True, text=True, encoding='utf8')
        (BUILD / 'class-comparison.txt').write_text(comparison.stdout + comparison.stderr, 'utf8')
        print(comparison.stdout, flush=True)
        comparison.check_returncode()
    baseline = json.loads((ROOT / 'provenance/baseline.json').read_text('utf8'))
    natives = {name: Path(config['native_source']) / name for name in baseline['native_hashes']}
    for name, path in natives.items():
        assert sha(path) == baseline['native_hashes'][name], 'Native input changed: ' + name
    hashes = {name: sha(path) for name, path in natives.items()}
    identity = hashlib.sha256(json.dumps(hashes, sort_keys=True).encode()).hexdigest()
    index = 'bundle=' + identity + '\nfiles=' + ','.join(sorted(natives)) + '\n'
    index += ''.join(name + '=' + hashes[name] + '\n' for name in sorted(natives))
    jar = BUILD / 'AimohPMXPreview.jar'
    temporary = jar.with_suffix('.jar.writing')
    with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(classes.rglob('*.class')): archive.write(path, path.relative_to(classes).as_posix())
        prefix = 'aimoh/pmxpreview/native/windows-x64/'
        archive.writestr(prefix + 'index.properties', index)
        for name in sorted(natives): archive.write(natives[name], prefix + name)
    with zipfile.ZipFile(temporary) as archive: assert archive.testzip() is None
    os.replace(temporary, jar)
    save(BUILD / 'build.json', {'status': 'PASS_FULL_SOURCE_BUILD_AND_RUNTIME_CLASS_PARITY' if compare_baseline else 'PASS_FULL_SOURCE_BUILD',
         'jar': str(jar), 'jar_sha256': sha(jar), 'jar_bytes': jar.stat().st_size,
         'reference_jar_sha256': sha(config['reference_jar']), 'java_sources': len(source),
         'native_hashes': hashes, 'native_rebuilt': False, 'runtime_class_baseline_compared': compare_baseline,
         'source_hashes': {p.relative_to(ROOT).as_posix(): sha(p) for p in source},
         'game_started': False, 'installed': False})
    print('Built: ' + str(jar), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compare-baseline', action='store_true', help='Require exact runtime class parity, ignoring compiler debug attributes.')
    build(parser.parse_args().compare_baseline)
