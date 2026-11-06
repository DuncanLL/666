"""GPU-test production source screen transport against the selected Eclipse geometry formula."""
from pathlib import Path
import argparse,json,subprocess,zipfile
from common import ROOT,BUILD,settings,environment,save,sha
from build import compile_java
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--jar',type=Path,default=BUILD/'render-support/AimohGF2Eclipse.jar');p.add_argument('--before',action='store_true');a=p.parse_args();c=settings();game=Path(c['game_root']);out=BUILD/'eclipse-screen'/('before'if a.before else'fixed');out.mkdir(parents=True,exist_ok=True)
    job=json.loads((BUILD/'shader-link-fix/fixed/jobs.json').read_text('utf8'))[0]
    with zipfile.ZipFile(BUILD/'render-support/Aimoh-Eclipse-GF2.zip')as z:
        name=next(n for n in z.namelist()if n.endswith('/dimensions/all_solid.vsh'));line=next(l.strip()for l in z.read(name).decode('utf8').splitlines()if 'gl_Position.xy = gl_Position.xy * RENDER_SCALE + RENDER_SCALE * gl_Position.w - gl_Position.w;'in l)
    (out/'world-transform.glsl').write_text(line,'utf8');cp=[a.jar.resolve(),c['viewpoint_jar'],c['zombiebuddy_jar'],BUILD/'AimohPMXPreview.jar',game,*game.glob('*.jar')]
    compile_java(c,[ROOT/'tools/java/EclipseScreenCheck.java'],out/'classes',cp)
    cmd=[str(game/'jre64/bin/java.exe'),'--enable-native-access=ALL-UNNAMED','-Djava.io.tmpdir='+str(BUILD/'tmp'),'-cp',';'.join(map(str,[out/'classes',*cp])),'EclipseScreenCheck',job['vertex'],job['fragment'],str(out/'world-transform.glsl'),str(out),str(a.before).lower()]
    r=subprocess.run(cmd,env=environment(),cwd=game,capture_output=True,text=True,encoding='utf8',errors='replace',timeout=45);(out/'run.log').write_text(r.stdout+r.stderr,'utf8');r.check_returncode();d=json.loads((out/'screen-parity.json').read_text('utf8'));d['addon_sha256']=sha(a.jar);save(out/'screen-parity.json',d);print(json.dumps(dict(status=d['status'],max_error=d['max_error'],cases=len(d['cases']),gpu=d['gpu']),ensure_ascii=False))
