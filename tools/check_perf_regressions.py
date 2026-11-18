"""Run the stock held-item bridge, pause contract and non-default indexed GL-state checks."""
import argparse
from pathlib import Path
import subprocess
import zipfile

from build import compile_java
from common import ROOT, environment, settings


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for arg in ('core','render','out'):
        p.add_argument('--'+arg,type=Path,required=True)
    args=p.parse_args()
    c=settings()
    game=Path(c['game_root'])
    out=args.out.resolve()
    out.mkdir(parents=True,exist_ok=True)
    deps=[args.core.resolve(),args.render.resolve(),c['viewpoint_jar'],c['zombiebuddy_jar'],*game.glob('*.jar')]
    names=['GripPhaseCheck','GripPauseCheck','IndexedScopeCheck']
    classes=out/'classes'
    compile_java(c,[ROOT/'tools/java'/(name+'.java') for name in names],classes,deps)
    agent=out/'grip-agent.jar'
    with zipfile.ZipFile(agent,'w') as z:
        z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nPremain-Class: gfl2.runtime.GripPhaseCheck\nCan-Retransform-Classes: true\nCan-Redefine-Classes: true\n\n')
        for file in classes.rglob('GripPhaseCheck*.class'):
            z.write(file,file.relative_to(classes).as_posix())
    for name in names:
        cmd=[str(game/'jre64/bin/java.exe'),'-ea','--add-opens=java.base/java.lang=ALL-UNNAMED',
             '--enable-native-access=ALL-UNNAMED','-Dgfl2.verifyGlScope=true','-Duser.home='+str(out/'home')]
        if name=='GripPhaseCheck':
            cmd.append('-javaagent:'+str(agent))
        package='viewpoint.render' if name=='IndexedScopeCheck' else 'gfl2.runtime'
        cmd.extend(['-cp',';'.join(map(str,[classes,*deps])),package+'.'+name])
        with (out/(name+'.log')).open('w',encoding='utf8') as log:
            subprocess.run(cmd,cwd=game,env=environment(),stdout=log,stderr=subprocess.STDOUT,check=True,timeout=120)
        print(name+' passed')


if __name__=='__main__':
    main()
