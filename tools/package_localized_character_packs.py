"""Stage the localized character packs, or validate them one at a time on a small disk."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import sys
from tempfile import TemporaryDirectory

from common import BUILD, ROOT, inventory, save, settings, sha


TRANSLATIONS = Path('42/media/lua/shared/Translate')
VOICE_PATCHES = {
    'GFL2Asteria': Path('42/media/lua/shared/GFL2PmxAsteriaVoice/Interface.lua'),
    'GFL2Yohee': Path('42/media/lua/client/ZZ_GFL2PmxYoheeVoice.lua'),
}


def properties(path):
    return dict(line.split('=', 1) for line in path.read_text('utf-8-sig').splitlines() if '=' in line)


def read_json(path):
    return json.loads(path.read_text('utf-8-sig'))


def sound_ids(module):
    names = set()
    folder = module / '42/media/scripts'
    media = (module / '42/media/sound').resolve(strict=True)
    for script in folder.glob('*Voice_Sounds.txt'):
        if script.name.endswith('NativeVoice_Sounds.txt'):
            continue
        text = script.read_text('utf-8-sig')
        parts = re.split(r'(?m)^\s*sound\s+([A-Za-z0-9_]+)\s*', text)[1:]
        for name, definition in zip(parts[::2], parts[1::2], strict=True):
            assert name not in names, (module.name, name)
            files = re.findall(r'(?m)^\s*file\s*=\s*([^,\r\n]+)', definition)
            assert files, (module.name, name)
            for relative in files:
                asset = (module / '42' / relative.strip().strip('"')).resolve(strict=True)
                assert asset.is_relative_to(media) and asset.is_file(), (module.name, name, relative)
            names.add(name)
    assert len(names) == 173, (module.name, len(names))
    return names


def stage_character(source, destination, pack, mod_id, registry, validate):
    relative = Path('Contents/mods') / mod_id
    module = source / relative
    allocation = properties(module / '42/mod.info')
    workshop = properties(source / 'workshop.txt')
    assert allocation['id'] == mod_id and allocation['require'] == '\\GFL2Exilium', (pack, allocation)
    assert re.fullmatch(r'\d+', workshop['id']), (pack, workshop['id'])
    definitions = read_json(module / f'42/media/gfl2_pmx/packs/{pack}.json')
    assert definitions['mod'] == pack, (pack, definitions['mod'])
    assert {model['id'] for model in definitions['models']} == set(registry['packs'][pack]), pack

    original = inventory(source)
    shutil.copytree(source, destination)
    files = ROOT / 'game/packs' / pack
    overlays = [path.relative_to(files) for path in (files / TRANSLATIONS).glob('*/*.json')]
    if pack in VOICE_PATCHES:
        overlays.append(VOICE_PATCHES[pack])
    for path in overlays:
        target = destination / relative / path
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(files / path, target)

    packaged = destination / relative
    names = sound_ids(packaged)
    for language in ('EN', 'KO'):
        captions = read_json(packaged / TRANSLATIONS / language / 'IG_UI.json')
        assert set(captions) == {'IGUI_GFL2_Subtitle_' + name for name in names}, (pack, language)
        assert all(isinstance(value, str) and value.strip() for value in captions.values()), (pack, language)
    readback = inventory(destination)
    overlay_names = {(relative / path).as_posix() for path in overlays}
    differences = {name for name, row in readback.items() if original.get(name) != row}
    assert differences <= overlay_names and original.keys() <= readback.keys(), (pack, differences)
    assert all(readback[(relative / path).as_posix()]['sha256'] == sha(files / path) for path in overlays), pack
    validate(destination)
    return {'pack': pack, 'mod_id': mod_id, 'workshop_id': workshop['id'], 'models': len(definitions['models']),
            'voice_cues': len(names), 'localized_files': {name: readback[name] for name in sorted(differences)}}


def check_characters(source, output=None):
    config = settings()
    source = source.resolve(strict=True)
    registry = read_json(ROOT / 'game/mod/42/media/gfl2_pmx/registry.json')
    ids = registry['pack_mod_ids']
    assert ids.keys() == registry['packs'].keys()
    if output is not None:
        output = output.resolve()
        assert not output.exists() and output.name.casefold() == 'workshop', output
        assert not output.is_relative_to(source) and not source.is_relative_to(output)
        required = sum(file.stat().st_size for mod in ids.values() for file in (source / mod).rglob('*') if file.is_file())
        output.parent.mkdir(parents=True, exist_ok=True)
        if shutil.disk_usage(output.parent).free < required:
            raise OSError(f'Need at least {required / (1024 ** 3):.1f} GiB free, or omit --output to validate one pack at a time.')

    game = Path(config['game_root'])
    sys.path.insert(0, config['python_dependencies'])
    import jpype

    BUILD.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(prefix='character-localization-', dir=output.parent if output is not None else BUILD) as folder:
        temporary = Path(folder)
        workshop = temporary / 'Workshop'
        workshop.mkdir()
        previous_directory = Path.cwd()
        os.chdir(game)
        jpype.startJVM(str(game / 'jre64/bin/server/jvm.dll'), '--enable-native-access=ALL-UNNAMED',
                       '-Djava.awt.headless=true', '-Duser.home=' + folder,
                       classpath=[str(game / 'projectzomboid.jar')], convertStrings=True)
        try:
            filesystem = jpype.JClass('zombie.ZomboidFileSystem').instance
            filesystem.setCacheDir(str(workshop.parent))
            filesystem.init()
            workshop_item = jpype.JClass('zombie.core.znet.SteamWorkshopItem')

            def validate(project):
                item = workshop_item(str(project))
                assert item.readWorkshopTxt(), project
                problem = item.validateContents()
                assert problem is None, (project, problem)

            results = []
            for pack, mod_id in ids.items():
                destination = workshop / mod_id
                try:
                    results.append(stage_character(source / mod_id, destination, pack, mod_id, registry, validate))
                    print(f'PASS {mod_id}: 173 voice cues and Workshop contents', flush=True)
                finally:
                    if output is None and destination.is_dir():
                        assert destination.resolve().parent == workshop.resolve(), destination
                        shutil.rmtree(destination)
            assert len({row['workshop_id'] for row in results}) == len(ids)
        finally:
            jpype.shutdownJVM()
            os.chdir(previous_directory)
        if output is not None:
            workshop.rename(output)
    save(BUILD / 'localized-character-pack-check.json', {
        'source': str(source), 'output': str(output) if output is not None else None,
        'gameplay_tested': False, 'packs': results,
    })


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path, help='Author Workshop directory with the existing role packs.')
    parser.add_argument('--output', type=Path, help='Fresh Workshop directory to retain all verified role packs.')
    args = parser.parse_args()
    check_characters(args.source, args.output)
