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


def entries(archive):
    names = [entry.filename for entry in archive.infolist() if not entry.is_dir()]
    assert len(names) == len(set(names)), 'Duplicate JAR entry'
    assert not any(re.match(r'META-INF/.*\.(SF|RSA|DSA|EC)$', name, re.I) for name in names), 'Signed input needs a new signing workflow'
    return set(names)


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
    assert 'id=3834454638' in (character / 'workshop.txt').read_text('utf-8-sig').splitlines()
    assert not source.with_suffix('.jar.zbs').exists(), 'The unified JAR needs fresh approval and cannot carry an old signature'
    pins = {path: sha(path) for path in (source, addon, host, zombie)}
    signature=host.with_suffix('.jar.zbs')
    if signature.is_file():pins[signature]=sha(signature)
    assert pins[source] == args.character_sha256
    assert report['status'] == 'PROTECTED_REVIEW_BUILD' and pins[addon] == report['jar_sha256']
    assert pins[host] == report['host_sha256']
    mod_info = (character / CHARACTER_MOD / '42/mod.info').read_text('utf-8-sig').splitlines()
    assert all(line in mod_info for line in ('id=AimohPmxPreview', 'javaPkgName=aimoh.pmxpreview', 'javaJarFile=media/java/client/AimohPMXPreview.jar'))
    assert 'require=\\ZombieBuddy,\\Viewpoint' in mod_info
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
        assert ENTRY_CLASSES <= person_entries and RENDER_STARTUP in render_entries
        assert not {name for name in (person_entries | render_entries) & (set(official.namelist()) | set(loader.namelist())) if name.endswith('.class')}

        stage = output.with_name('R116.preparing')
        assert not stage.exists()
        original = inventory(character)
        shutil.copytree(character, stage)
        target = stage / CHARACTER_MOD / CHARACTER_JAR
        temporary = target.with_suffix('.jar.writing')
        with zipfile.ZipFile(temporary, 'w') as joined:
            for name in sorted(person_entries):
                data = (overlay / name).read_bytes() if name in ENTRY_CLASSES else person.read(name)
                write(joined, name, data)
            for name in sorted(render_entries):
                write(joined, name, render.read(name))
        with zipfile.ZipFile(temporary) as joined:
            assert joined.testzip() is None and entries(joined) == person_entries | render_entries
            assert all(joined.read(n) == person.read(n) for n in person_entries - ENTRY_CLASSES)
            assert all(joined.read(n) == render.read(n) for n in render_entries)
        os.replace(temporary, target)
        info = stage / CHARACTER_MOD / '42/mod.info'
        info.write_text('\n'.join('modversion='+args.version if row==version else row for row in mod_info)+'\n', 'utf8')
        result = inventory(stage)
        expected_changes = {CHARACTER_MOD+'/42/mod.info', CHARACTER_MOD+'/'+CHARACTER_JAR}
        assert original.keys() == result.keys() and {name for name in original if original[name]!=result[name]} == expected_changes
        assert pins == {path: sha(path) for path in pins}
        stage.rename(output)

    save(work/'build.json', dict(status='UNIFIED_WORKSHOP_BUILT', version=args.version,
         character_version=version.split('=',1)[1], render_version=report['version'], workshop_id='3834454638',
         project=str(output), jar_sha256=result[CHARACTER_MOD+'/'+CHARACTER_JAR]['sha256'],
         character_sha256=pins[source], render_sha256=pins[addon], host_sha256=pins[host],
         signature_sha256=pins.get(signature), loader_sha256=pins[zombie],
         catalog_sha256=result[CHARACTER_MOD+'/42/media/aimoh_gf2/characters.json']['sha256'],
         character_workshop_files=len(result), changed_character_entries=sorted(ENTRY_CLASSES),
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
