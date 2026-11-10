"""Split the 0.3.37 Workshop release into 0.3.38 and three optional data packs."""
import argparse
from copy import deepcopy
import json
from pathlib import Path
import shutil
from zipfile import ZipFile

from common import save, sha


VERSION = '0.3.38'
MOD = Path('Contents/mods/AimohPmxPreview')
MEDIA = Path('42/media')
REGISTRY = MEDIA / 'aimoh_pmx/registry.json'
GF2 = MEDIA / 'aimoh_gf2/characters.json'
ROOM = MEDIA / 'ui/AimohPMXReadyRoom/catalog.json'
JAR = MEDIA / 'java/client/AimohPMXPreview.jar'
INFO = Path('42/mod.info')
PACKS = {
    'R116Springfield': ('pmx-225af1a054faefb0', '春田（睡醒的人鱼）'),
    'R116Faelynn': ('pmx-293917f24c2bd387', '芙铃（忻跃阳炎）'),
    'R116OTs14': ('pmx-782f06826adb1307', '闪电（OTs-14）'),
}
LEGACY = ('R116Alva', 'R116Centaureissi')
VOICE = tuple(MEDIA / name for name in (
    'lua/client/ZZ_AimohPMXFaelynnVoice.lua', 'lua/shared/AimohPMXFaelynnVoice',
    'scripts/AimohPMXFaelynnVoice_Sounds.txt', 'scripts/AimohPMXFaelynnNativeVoice_Sounds.txt',
    'sound/AimohPMXFaelynn',
))


def read(path):
    return json.loads(path.read_text('utf-8-sig'))


def values(obj, kind):
    if isinstance(obj, kind):
        yield obj
    if isinstance(obj, dict):
        for value in obj.values():
            yield from values(value, kind)
    elif isinstance(obj, list):
        for value in obj:
            yield from values(value, kind)


def resources(catalog):
    out = set()
    for row in values(catalog, dict):
        if row.get('storage') == 'mod':
            path = Path(row['path'])
            assert path.parent.as_posix() == 'gf2/data' and path.stem == row['sha256']
            out.add(GF2.parent / 'data' / path.name)
    return out


def material_ids(models):
    ids = set()
    for model in models:
        ids.update(part['source_material'] for part in model['parts'])
        ids.update(map(int, model['renderer_frames']['materials']))
    return ids


def catalog_subset(full, models, ids, preserve_unused=False):
    remap = {old: new for new, old in enumerate(sorted(ids))}
    materials = [dict(full['materials'][old], id=new) for old, new in remap.items()]
    model_rows = deepcopy(models)
    for row in model_rows:
        for part in row['parts']:
            part['source_material'] = remap[part['source_material']]
        frame = row['renderer_frames']
        frame['materials'] = {str(remap[int(old)]): v for old, v in frame['materials'].items()}
    names = {row['name'] for row in materials}
    programs = [row for row in full['programs'] if row['material'] in names]

    def textures(rows):
        return {slot['resource'] for row in rows for slot in row['texture_bindings'].values() if 'resource' in slot}

    def programs_used(rows):
        return {variant['sha256'] for row in rows for variant in row['programs']}

    refs = {'textures': (textures(materials), textures(full['materials'])),
            'shader_property_defaults': (names, {row['name'] for row in full['materials']})}
    for key in ('glsl', 'early_fragments', 'consumed'):
        refs[key] = (programs_used(programs), programs_used(full['programs']))
    return {'materials': materials, 'programs': programs, 'models': model_rows, **{
        key: {k: v for k, v in full[key].items() if k in used or (preserve_unused and k not in originally_used)}
        for key, (used, originally_used) in refs.items()
    }}


def ui_files(obj):
    return {Path('42') / text for text in values(obj, str) if text.startswith('media/ui/')}


def copy_file(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)


def version_info(text, prior):
    for key, old, new in (('modversion', prior, VERSION), ('versionMin', '42.21', '42.22'), ('versionMax', '42.21', '42.22')):
        assert f'{key}={old}\n' in text or (key != 'modversion' and f'{key}={new}\n' in text)
        text = text.replace(f'{key}={old}\n', f'{key}={new}\n')
    return text


def build(workshop, out, expected_jar):
    source = workshop / 'R116' / MOD
    assert sha(source / JAR) == expected_jar and not (source / JAR.with_suffix('.jar.zbs')).exists()
    assert 'id=3834454638' in (workshop / 'R116/workshop.txt').read_text('utf-8-sig').splitlines()
    assert not out.exists()
    registry, catalog, ui = (read(source / file) for file in (REGISTRY, GF2, ROOM))
    full = catalog['characters']
    assert [r['id'] for r in full['materials']] == list(range(len(full['materials'])))
    models = {row['pmx_sha256']: row for row in full['models']}
    original_entries = {row['id']: row for row in registry['models']}
    wanted = {model for model, _ in PACKS.values()}
    assert wanted <= original_entries.keys() and set(registry['packs']) == set(LEGACY)
    fingerprints = {model: read(source / REGISTRY.parent / original_entries[model]['profile'])['pmx_sha256'] for model in wanted}
    retained = [row for row in full['models'] if row['pmx_sha256'] not in fingerprints.values()]
    removed_ids = material_ids(full['models']) - material_ids(retained)
    core = catalog_subset(full, retained, set(range(len(full['materials']))) - removed_ids, True)
    core_data = resources(core)
    core_ui = {key: val for key, val in ui['models'].items() if key not in wanted}
    pack_paths = {}
    for mod in LEGACY:
        project = workshop / mod
        prior = project / 'Contents/mods' / mod
        fragment = read(prior / GF2.parent / 'packs' / f'{mod}.json')
        for relative in resources(fragment):
            if not (prior / relative).is_file():
                assert (source / relative).is_file(), relative
                core_data.add(relative)
        shutil.copytree(project, out / mod)
        info = out / mod / 'Contents/mods' / mod / INFO
        info.write_text(version_info(info.read_text('utf-8-sig'), '0.3.20'), 'utf8')
    moved_labels = set()
    moved_ui = set()
    for mod, (model, title) in PACKS.items():
        row = original_entries[model]
        fingerprint = fingerprints[model]
        shader_model = models[fingerprint]
        shard = catalog_subset(full, [shader_model], material_ids([shader_model]))
        desc = ui['models'][model]
        labels = {name: ui['labels'][name] for name in set(values(desc, str)) & ui['labels'].keys()}
        moved_labels.update(labels)
        core_ui[model] = {key: desc[key] for key in ('character', 'skin', 'portrait')} | {'pack': mod}
        project = out / mod
        target = project / 'Contents/mods' / mod
        (target / 'common').mkdir(parents=True)
        (target / '42').mkdir()
        (target / INFO).write_text(
            f"name=R116 - {title}\nid={mod}\nrequire=\\AimohPmxPreview\nmodversion={VERSION}\nversionMin=42.22\nversionMax=42.22\n", 'utf8')
        (project / 'workshop.txt').write_text(
            f'version=1\ntitle=R116 - {title}\ndescription=\u200b\ntags=Build 42\nvisibility=unlisted\n', 'utf8')
        copy_file(workshop / f'{LEGACY[0]}/preview.png', project / 'preview.png')
        relative_model = REGISTRY.parent / Path(row['model']).parent
        assert sha(source / REGISTRY.parent / row['model']) == fingerprint
        shutil.copytree(source / relative_model, target / relative_model)
        with ZipFile(source / JAR) as jar:
            chest = jar.read(f'aimoh/pmxpreview/chest/{fingerprint}.bin')
        assert chest.startswith(b'ACSB0001' + bytes.fromhex(fingerprint))
        (target / relative_model / 'chest.bin').write_bytes(chest)
        save(target / REGISTRY.parent / 'packs' / f'{mod}.json', dict(format=1, mod=mod, models=[row]))
        save(target / GF2.parent / 'packs' / f'{mod}.json', dict(format=1, mod=mod, characters=shard))
        save(target / ROOM.parent / 'packs' / f'{mod}.json', dict(models={model: desc}, labels=labels))
        image_paths = ui_files((desc, labels)) - {Path('42') / desc['portrait']}
        moved_ui.update(image_paths)
        pack_data = resources(shard) - core_data
        assets = pack_data | image_paths
        for relative in sorted(assets):
            copy_file(source / relative, target / relative)
            if relative in pack_data:
                assert sha(target / relative) == relative.stem, relative
        if mod == 'R116Faelynn':
            for relative in VOICE:
                if (source / relative).is_dir():
                    shutil.copytree(source / relative, target / relative)
                else:
                    copy_file(source / relative, target / relative)
        pack_paths[mod] = relative_model

    still_used = set(values(core_ui, str))
    core_labels = {k: v for k, v in ui['labels'].items() if k not in moved_labels or k in still_used}
    registry['models'] = [row for row in registry['models'] if row['id'] not in wanted]
    registry['packs'].update({mod: [model] for mod, (model, _) in PACKS.items()})
    ui['models'], ui['labels'] = core_ui, core_labels
    ui['optionalModels'] += [model for model, _ in PACKS.values()]
    # Other active modules still resolve their shared resources through the R116 VFS.
    excluded = (resources(full) - core_data) | (moved_ui - ui_files(ui)) | set(pack_paths.values()) | set(VOICE)
    excluded_from_workshop = {MOD / path for path in excluded}

    def ignore(directory, names):
        return [name for name in names if (Path(directory) / name).relative_to(workshop / 'R116') in excluded_from_workshop]

    shutil.copytree(workshop / 'R116', out / 'R116', ignore=ignore)
    target = out / 'R116' / MOD
    save(target / REGISTRY, registry)
    save(target / GF2, catalog | {'characters': core})
    save(target / ROOM, ui)
    (target / INFO).write_text(version_info((source / INFO).read_text('utf-8-sig'), '0.3.37'), 'utf8')
    skip = {f'aimoh/pmxpreview/chest/{fp}.bin' for fp in fingerprints.values()}
    with ZipFile(source / JAR) as original, ZipFile(target / JAR, 'w') as updated:
        assert skip <= set(original.namelist())
        for item in original.infolist():
            if item.filename not in skip:
                updated.writestr(item, original.read(item))
    with ZipFile(source / JAR) as original, ZipFile(target / JAR) as updated:
        assert updated.testzip() is None and set(updated.namelist()) == set(original.namelist()) - skip
        assert all(original.read(name) == updated.read(name) for name in updated.namelist())
    removed = {path.relative_to(source).as_posix(): sha(path)
               for root in excluded for path in ([source / root] if (source / root).is_file() else (source / root).rglob('*'))
               if path.is_file()}
    changed = {path.as_posix(): {'before': sha(source / path), 'after': sha(target / path)}
               for path in (REGISTRY, GF2, ROOM, JAR, INFO)}
    save(out.parent / 'build.json', dict(version=VERSION, packs=PACKS, prior_packs=LEGACY,
         core_models=len(registry['models']), core_gf2_models=len(retained), removed=removed, changed=changed))
    print(f'Built six Workshop projects in {out}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workshop', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--core-sha256', required=True)
    args = parser.parse_args()
    assert args.out.name == 'Workshop'
    build(args.workshop.resolve(strict=True), args.out.resolve(), args.core_sha256)
