"""Assemble a single R116 Workshop item from verified character and sealed render builds."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

from build import compile_java
from build_render_support import write
from common import BUILD, ROOT, environment, inventory, save, settings, sha


CHARACTER_MOD = 'Contents/mods/AimohPmxPreview'
CHARACTER_JAR = '42/media/java/client/AimohPMXPreview.jar'
RENDER_JAR = 'Contents/mods/AimohGF2Eclipse/42/media/java/client/AimohGF2Eclipse.jar'
ENTRY_CLASSES = {'aimoh/pmxpreview/Main.class', 'aimoh/pmxpreview/RenderPatch.class'}
RENDER_STARTUP = 'aimoh/gf2/bridge/UnifiedStartup.class'
RENDER_NOTICE_SOURCE = 'R116-RENDER-NOTICE.txt'
RENDER_NOTICE = 'META-INF/gfl2/rendering/author-notice.txt'


def entries(archive):
    names = [entry.filename for entry in archive.infolist() if not entry.is_dir()]
    assert len(names) == len(set(names)), 'Duplicate JAR entry'
    assert not any(re.match(r'META-INF/.*\.(SF|RSA|DSA|EC)$', name, re.I) for name in names), 'Signed input needs a new signing workflow'
    return set(names)


def trim_material_provenance(catalog):
    for material in catalog["characters"]["materials"]:
        material.pop("unresolved_material_fields", None)
        for binding in material["bindings"]:
            binding.pop("origin", None)
            binding.pop("conversion", None)


def trim_render_catalogs(project):
    changed = set()
    for root in sorted((project / "Contents/mods").glob("*/42/media/gfl2_rendering")):
        for file in [root / "characters.json", *sorted((root / "packs").glob("*.json"))]:
            if not file.is_file():
                continue
            raw = json.loads(file.read_text("utf-8-sig"))
            before = json.dumps(raw)
            trim_material_provenance(raw)
            if json.dumps(raw) != before:
                file.write_text(json.dumps(raw, ensure_ascii=False, separators=(",", ":")) + "\n", "utf8")
                changed.add(file.relative_to(project).as_posix())
    return changed


def assemble(args):
    config = settings()
    game = Path(config['game_root'])
    host = Path(config['viewpoint_jar'])
    zombie = Path(config['zombiebuddy_jar'])
    character = args.character_project.resolve(strict=True)
    addon = args.render_project.resolve(strict=True) / RENDER_JAR
    source = character / CHARACTER_MOD / CHARACTER_JAR
    report = json.loads(args.render_report.read_text('utf8'))
    assert re.fullmatch(r'\d+\.\d+\.\d+', args.version), 'Use an explicit combined version'
    assert {path.name for path in (character / 'Contents/mods').iterdir()} == {'AimohPmxPreview'}
    metadata = dict(line.split('=', 1) for line in (character / 'workshop.txt').read_text('utf-8-sig').splitlines() if '=' in line)
    assert metadata.get('title') == '少前2追放角色框架'
    workshop_id = metadata.get('id')
    assert not source.with_suffix('.jar.zbs').exists(), 'The unified JAR needs fresh approval and cannot carry an old signature'
    pins = {path: sha(path) for path in (source, addon, host, zombie)}
    signature=host.with_suffix('.jar.zbs')
    if signature.is_file():pins[signature]=sha(signature)
    assert pins[source] == args.character_sha256
    assert report['status'] == 'PROTECTED_REVIEW_BUILD' and pins[addon] == report['jar_sha256']
    assert pins[host] == report['host_sha256']
    mod_info = (character / CHARACTER_MOD / '42/mod.info').read_text('utf-8-sig').splitlines()
    assert all(line in mod_info for line in ('id=GFL2Exilium', 'javaPkgName=aimoh.pmxpreview', 'javaJarFile=media/java/client/AimohPMXPreview.jar'))
    assert 'require=\\ZombieBuddy' in mod_info
    version, = [line for line in mod_info if line.startswith('modversion=')]

    output = args.output.resolve()
    assert output.is_relative_to(BUILD.resolve()) and not output.exists()
    assert output.parent.name == 'Workshop' and output.name == 'R116'
    work = output.parent.parent
    overlay = work / 'wired-classes'
    tool = work / 'wiring-tool'
    assert not overlay.exists() and not tool.exists()
    compile_java(config, [ROOT/'tools/java/WireUnifiedCharacter.java'], tool, [zombie])
    subprocess.run([str(game/'jre64/bin/java.exe'), '-cp', f'{tool};{zombie}', 'WireUnifiedCharacter', str(source), str(overlay)], check=True, env=environment())
    assert {p.relative_to(overlay).as_posix() for p in overlay.rglob('*.class')} == ENTRY_CLASSES

    with zipfile.ZipFile(source) as person, zipfile.ZipFile(addon) as render, zipfile.ZipFile(host) as official, zipfile.ZipFile(zombie) as loader:
        person_entries, render_entries = entries(person), entries(render)
        assert not person_entries & render_entries, sorted(person_entries & render_entries)
        assert not {RENDER_NOTICE_SOURCE, RENDER_NOTICE} & (person_entries | render_entries)
        assert ENTRY_CLASSES <= person_entries and RENDER_STARTUP in render_entries
        assert not {name for name in (person_entries | render_entries) & (set(official.namelist()) | set(loader.namelist())) if name.endswith('.class')}

        stage = output.with_name('R116.preparing')
        assert not stage.exists()
        original = inventory(character)
        shutil.copytree(character, stage)
        trimmed = trim_render_catalogs(stage)
        target = stage / CHARACTER_MOD / CHARACTER_JAR
        temporary = target.with_suffix('.jar.writing')
        notice = (ROOT / 'render-support' / RENDER_NOTICE_SOURCE).read_bytes()
        with zipfile.ZipFile(temporary, 'w') as joined:
            write(joined, RENDER_NOTICE, notice)
            for name in sorted(person_entries):
                data = (overlay / name).read_bytes() if name in ENTRY_CLASSES else person.read(name)
                write(joined, name, data)
            for name in sorted(render_entries):
                write(joined, name, render.read(name))
        with zipfile.ZipFile(temporary) as joined:
            assert joined.testzip() is None and entries(joined) == person_entries | render_entries | {RENDER_NOTICE}
            assert joined.read(RENDER_NOTICE) == notice
            assert all(joined.read(n) == person.read(n) for n in person_entries - ENTRY_CLASSES)
            assert all(joined.read(n) == render.read(n) for n in render_entries)
        os.replace(temporary, target)
        info = stage / CHARACTER_MOD / '42/mod.info'
        info.write_text('\n'.join('modversion='+args.version if row==version else row for row in mod_info)+'\n', 'utf8')
        result = inventory(stage)
        expected_changes = {CHARACTER_MOD+'/42/mod.info', CHARACTER_MOD+'/'+CHARACTER_JAR}
        assert original.keys() == result.keys() and {name for name in original if original[name]!=result[name]} == expected_changes | trimmed
        assert pins == {path: sha(path) for path in pins}
        stage.rename(output)

    save(work/'build.json', dict(status='UNIFIED_WORKSHOP_BUILT', version=args.version,
         character_version=version.split('=',1)[1], render_version=report['version'], workshop_id=workshop_id,
         project=str(output), jar_sha256=result[CHARACTER_MOD+'/'+CHARACTER_JAR]['sha256'],
         character_sha256=pins[source], render_sha256=pins[addon], host_sha256=pins[host],
         signature_sha256=pins.get(signature), loader_sha256=pins[zombie],
         catalog_sha256=result[CHARACTER_MOD+'/42/media/gfl2_rendering/characters.json']['sha256'],
         character_workshop_files=len(result), changed_character_entries=sorted(ENTRY_CLASSES),
         trimmed_catalogs=sorted(trimmed),
         installed=False, uploaded=False))
    print(f'Unified R116 Workshop project: {output}')


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--character-project',type=Path,required=True)
    parser.add_argument('--character-sha256',required=True)
    parser.add_argument('--render-project',type=Path,required=True)
    parser.add_argument('--render-report',type=Path,required=True)
    parser.add_argument('--version',required=True)
    parser.add_argument('--output',type=Path,required=True)
    assemble(parser.parse_args())
