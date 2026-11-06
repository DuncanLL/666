"""Build a game-recognized Workshop project; external character resources remain outside Git."""
from pathlib import Path
import argparse
import json
import os
import shutil
import subprocess
import sys
import zipfile
from common import ROOT, BUILD, settings, sha, save, inventory


def record(output, jar):
    files = inventory(output)
    save(BUILD / 'workshop-manifest.json', files)
    git = subprocess.run(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], capture_output=True, text=True)
    dirty = subprocess.run(['git', '-C', str(ROOT), 'status', '--porcelain'], capture_output=True, text=True)
    save(BUILD / 'workshop.json', {'status': 'WORKSHOP_CANDIDATE_BUILT_NOT_INSTALLED_OR_UPLOADED',
         'root': str(output), 'files': len(files), 'bytes': sum(v['bytes'] for v in files.values()),
         'jar_sha256': sha(jar), 'source_commit': git.stdout.strip() if git.returncode == 0 else None,
         'source_dirty': bool(dirty.stdout.strip()), 'workbench_included': False,
         'shader_addon_included': False, 'shader_pack_included': False,
         'game_started': False, 'workshop_uploaded': False})


def package(output, verify_existing=False):
    config = settings()
    build = json.loads((BUILD / 'build.json').read_text('utf8'))
    jar = Path(build['jar'])
    assert sha(jar) == build['jar_sha256']
    for relative, fingerprint in build['source_hashes'].items():
        assert sha(ROOT / relative) == fingerprint, 'Source changed after build: ' + relative
    source = Path(config['asset_root']).resolve()
    output = Path(output).resolve()
    manifest = json.loads((ROOT / 'resources/manifest.json').read_text('utf8'))['files']
    if verify_existing:
        expected = dict(manifest)
        expected.update({name: row for name, row in inventory(ROOT / 'game/mod').items() if name != 'common/.gitkeep'})
        expected['42/media/java/client/AimohPMXPreview.jar'] = {'bytes': jar.stat().st_size, 'sha256': sha(jar)}
        assert inventory(output / 'Contents/mods/AimohPmxPreview') == expected, 'Existing candidate differs from the current source/resources.'
        assert set(p.name for p in output.iterdir()) == {'Contents', 'workshop.txt', 'preview.png'}
        record(output, jar)
        print('Existing Workshop project verified: ' + str(output), flush=True)
        return
    assert not output.exists(), 'Use a fresh output folder; existing Workshop projects are not overwritten automatically.'
    assert not output.is_relative_to(source) and not source.is_relative_to(output)
    stage = output.with_name(output.name + '.preparing')
    assert not stage.exists()
    mod = stage / 'Contents/mods/AimohPmxPreview'
    mod.mkdir(parents=True)
    for relative, row in manifest.items():
        path = (source / relative).resolve()
        assert path.is_relative_to(source) and path.is_file(), relative
        assert path.stat().st_size == row['bytes'] and sha(path) == row['sha256'], 'External asset changed: ' + relative
        dest = mod / relative
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, dest)
    # The repository owns these configuration, Lua and sound-declaration files.
    shutil.copytree(ROOT / 'game/mod', mod, dirs_exist_ok=True)
    runtime = mod / '42/media/java/client/AimohPMXPreview.jar'
    runtime.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(jar, runtime)
    sys.path.insert(0, config['python_dependencies'])
    from PIL import Image
    Image.new('RGB', (256, 256), (32, 32, 32)).save(stage / 'preview.png')
    (stage / 'workshop.txt').write_text('version=1\ntitle=R116\ndescription=\u200b\ntags=\nvisibility=unlisted\n', 'utf8')
    (mod / 'common').mkdir(exist_ok=True)
    # .gitkeep is useful in Git only, not in the Workshop payload.
    (mod / 'common/.gitkeep').unlink(missing_ok=True)
    forbidden = {'.dll', '.exe', '.zip', '.bat', '.app', '.so', '.sh', '.dylib'}
    assert not [p for p in stage.rglob('*') if p.is_file() and p.suffix.lower() in forbidden]
    with zipfile.ZipFile(runtime) as archive: assert archive.testzip() is None
    registry = json.loads((mod / '42/media/aimoh_pmx/registry.json').read_text('utf8'))['models']
    assert next(row for row in registry if row['id'] == 'pmx-f5b2807685b18a60')['hidden']
    output.parent.mkdir(parents=True, exist_ok=True)
    os.replace(stage, output)
    runtime = output / 'Contents/mods/AimohPmxPreview/42/media/java/client/AimohPMXPreview.jar'
    record(output, runtime)
    print('Workshop project: ' + str(output), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=BUILD / 'workshop/R116')
    parser.add_argument('--verify-existing', action='store_true', help='Read back an already generated candidate instead of copying its resources again.')
    args = parser.parse_args()
    package(args.output, args.verify_existing)
