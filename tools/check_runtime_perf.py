"""Measure room loading, actual DanRP frames, retargeting and the enabled material catalog."""
import argparse
import json
from pathlib import Path
import subprocess
import zipfile

from build import compile_java
from common import ROOT, settings, environment, sha


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('core', 'render', 'workshop', 'idle-f32', 'out'):
        parser.add_argument('--'+name, required=True, type=Path)
    parser.add_argument('--model', required=True)
    parser.add_argument('--count-finite', action='store_true', help='Count actual Float.isFinite visits in a separate instrumented JVM run')
    parser.add_argument('--all-poses', action='store_true', help='Save every registered model palette for an independent elementwise comparison')
    args = parser.parse_args()
    config = settings()
    game = Path(config['game_root'])
    out = args.out.resolve()
    out.mkdir(exist_ok=True, parents=True)
    cp = [args.core.resolve(), args.render.resolve(), config['viewpoint_jar'], config['zombiebuddy_jar'], *game.glob('*.jar')]
    classes = out/'probe'
    probes = ['RuntimePerfProbe']
    if args.all_poses:
        probes.append('RetargetParityCheck')
    sources = probes + (['FrameQueryCounter'] if args.count_finite else [])
    compile_java(config, [ROOT/'tools/java'/f'{p}.java' for p in sources], classes, cp)
    base = [str(game/'jre64/bin/java.exe'), '-Xmx4g', '--enable-native-access=ALL-UNNAMED',
            '-Duser.home='+str(out/'home'), '-cp', ';'.join(map(str,[classes,*cp]))]
    if args.count_finite:
        agent=out/'finite-agent.jar'
        with zipfile.ZipFile(agent,'w') as z:
            z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nPremain-Class: FrameQueryCounter\nCan-Retransform-Classes: true\n\n')
            for f in classes.glob('FrameQueryCounter*.class'):
                z.write(f,f.name)
        base.extend(['-Dgfl2.measureFinite=true','-javaagent:'+str(agent)+'='+config['zombiebuddy_jar']])
    for probe in probes:
        if probe == 'RuntimePerfProbe':
            inputs = [args.workshop.resolve(),args.model,out,ROOT/'render-support/native-pack',args.idle_f32.resolve()]
        else:
            inputs = [args.workshop.resolve(),out/'parity',args.idle_f32.resolve(),args.model]
        with (out/f'{probe}.log').open('w',encoding='utf8') as log:
            subprocess.run([*base,'gfl2.runtime.'+probe,*map(str,inputs)], cwd=game, env=environment(),
                           stdout=log, stderr=subprocess.STDOUT, check=True)
    result = json.loads((out/'runtime.json').read_text('utf8'))
    result.update(core_sha256=sha(args.core), render_sha256=sha(args.render), idle_sha256=sha(args.idle_f32))
    (out/'runtime.json').write_text(json.dumps(result,indent=2)+'\n','utf8')
    print(json.dumps(result))


if __name__=='__main__':
    main()
