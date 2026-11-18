"""Replay the Native and Eclipse frame gate against a private, normally wired pair of builds."""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import zipfile

from build import compile_java
from common import ROOT, settings


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    for name in ('core', 'render', 'fixture', 'workshop', 'out'):
        cli.add_argument('--'+name, required=True, type=Path)
    cli.add_argument('--night', action='store_true')
    cli.add_argument('--audit', action='store_true')
    cli.add_argument('--catalog', action='store_true')
    opt = cli.parse_args()
    config = settings()
    game = Path(config['game_root'])
    java = game/'jre64/bin/java.exe'
    fixture = opt.fixture.resolve(strict=True)
    work = opt.out.resolve()
    work.mkdir(parents=True, exist_ok=True)
    core = opt.core.resolve(strict=True)
    render = opt.render.resolve(strict=True)
    deps = [config['viewpoint_jar'], config['zombiebuddy_jar'], game/'projectzomboid.jar']
    classes = work/'probe-classes'
    inputs = list((fixture/'java').glob('*.java'))
    for name in ('FrameQueryCounter', 'UnifiedStartupCheck', 'WireUnifiedCharacter'):
        inputs.append(ROOT/'tools/java'/f'{name}.java')
    if opt.catalog:
        inputs.append(ROOT/'tools/java/CatalogPerfCheck.java')
    compile_java(config, inputs, classes, [core, render, *deps])
    wired = work/'wired'
    subprocess.run([str(java), '-cp', f'{classes};{deps[1]}', 'WireUnifiedCharacter', str(core), str(wired)], check=True)
    joined = work/'unified.jar'
    with zipfile.ZipFile(core) as person, zipfile.ZipFile(render) as light, zipfile.ZipFile(joined, 'w', zipfile.ZIP_DEFLATED) as dest:
        assert not set(person.namelist()).intersection(light.namelist())
        for name in person.namelist():
            path = wired/name
            dest.writestr(name, path.read_bytes() if path.is_file() else person.read(name))
        for name in light.namelist():
            dest.writestr(name, light.read(name))
    native = work/'natives'
    native.mkdir(exist_ok=True)
    with zipfile.ZipFile(core) as person:
        for name in person.namelist():
            if '/native/' in name and name.endswith('.dll'):
                (native/Path(name).name).write_bytes(person.read(name))
    for filename, cls in [('counter.jar', 'FrameQueryCounter'), ('capture.jar', 'RenderSupportTestAgent')]:
        with zipfile.ZipFile(work/filename, 'w') as agent:
            agent.writestr('META-INF/MANIFEST.MF', f'Manifest-Version: 1.0\nPremain-Class: {cls}\nCan-Retransform-Classes: true\nCan-Redefine-Classes: true\n\n')
            for path in classes.glob(cls+'*.class'):
                agent.write(path,path.name)
    env = dict(os.environ, WORLD_WALL_RECEIVER='1', WORLD_ROOF='1' if opt.night else '0',
               VERIFY_SCOPE='true' if opt.audit else 'false')
    cmd = [sys.executable, str(fixture/'scene.py'), str(work), str(opt.workshop.resolve()),
           str(ROOT/'.local/paths.json'), 'night' if opt.night else 'day', 'catalog' if opt.catalog else 'draw']
    with (work/'scene.log').open('w',encoding='utf8') as log:
        subprocess.run(cmd, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
    print(work/('night' if opt.night else 'day')/'query-counts.json')


if __name__ == '__main__':
    main()
