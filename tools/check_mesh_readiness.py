"""Replay a native/Iris profile switch with real PMX meshes in a hidden context."""
from pathlib import Path
import argparse,json,subprocess
from common import ROOT,BUILD,settings,environment,save,sha
from build import compile_java

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--jar',type=Path,default=BUILD/'AimohPMXPreview.jar');p.add_argument('--before',action='store_true');a=p.parse_args()
    c=settings();game=Path(c['game_root']);out=BUILD/'mesh-readiness'/('before'if a.before else'fixed');out.mkdir(parents=True,exist_ok=True)
    cp=[a.jar.resolve(),Path(c['viewpoint_jar']),Path(c['zombiebuddy_jar']),*game.glob('*.jar')]
    compile_java(c,[ROOT/'tools/java/MeshReadinessCheck.java'],out/'classes',cp)
    cmd=[str(game/'jre64/bin/java.exe'),'--enable-native-access=ALL-UNNAMED','-Djava.io.tmpdir='+str(BUILD/'tmp'),'-cp',';'.join(map(str,[out/'classes',*cp])),'aimoh.pmxpreview.MeshReadinessCheck',str(Path(c['asset_root'])/'42'),str(out.resolve()),str(a.before).lower()]
    r=subprocess.run(cmd,cwd=game,env=environment(),capture_output=True,text=True,encoding='utf8',timeout=90);(out/'run.log').write_text(r.stdout+r.stderr,'utf8')
    if r.returncode:print((r.stdout+r.stderr)[-4000:]);r.check_returncode()
    result=json.loads((out/'result.json').read_text('utf8'));result['jar_sha256']=sha(a.jar);save(out/'result.json',result);print(json.dumps(result,ensure_ascii=False))
