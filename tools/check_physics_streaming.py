"""Check visibility/history and chest pause/resume without starting a game."""
from pathlib import Path
import argparse,json,subprocess
from common import ROOT,BUILD,settings,environment,save,sha
from build import compile_java

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--jar',type=Path,default=BUILD/'AimohPMXPreview.jar');a=p.parse_args()
    c=settings();game=Path(c['game_root']);out=BUILD/'physics-candidate-0.3.11/streaming';out.mkdir(parents=True,exist_ok=True)
    cp=[a.jar.resolve(),Path(c['viewpoint_jar']),Path(c['zombiebuddy_jar']),*game.glob('*.jar')]
    compile_java(c,[ROOT/'tools/java/PhysicsStreamingCheck.java'],out/'classes',cp)
    command=[str(game/'jre64/bin/java.exe'),'--enable-native-access=ALL-UNNAMED','-Djava.io.tmpdir='+str(BUILD/'tmp'),'-cp',';'.join(map(str,[out/'classes',*cp])),
             'aimoh.pmxpreview.PhysicsStreamingCheck',str(Path(c['asset_root'])/'42'),str(out.resolve())]
    run=subprocess.run(command,cwd=game,env=environment(),capture_output=True,text=True,encoding='utf8',timeout=180)
    (out/'run.log').write_text(run.stdout+run.stderr,'utf8')
    if run.returncode:print((run.stdout+run.stderr)[-5000:]);run.check_returncode()
    result=json.loads((out/'result.json').read_text('utf8'));result['jar_sha256']=sha(a.jar);save(out/'result.json',result);print(json.dumps(result,ensure_ascii=False))
