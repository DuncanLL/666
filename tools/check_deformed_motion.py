"""Check real host vertex programs with PMX motion and inherited static attributes."""
from pathlib import Path
import argparse,json,subprocess,zipfile
from common import ROOT,BUILD,settings,environment,save,sha
from build import compile_java

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jar',type=Path,default=BUILD/'AimohPMXPreview.jar')
    p.add_argument('--before',action='store_true');a=p.parse_args()
    c=settings();game=Path(c['game_root']);out=BUILD/'deformed-motion'/('before'if a.before else'fixed');out.mkdir(parents=True,exist_ok=True)
    cp=[a.jar.resolve(),Path(c['viewpoint_jar']),Path(c['zombiebuddy_jar']),*game.glob('*.jar')]
    classes=out/'classes';compile_java(c,[ROOT/'tools/java/DeformedMotionCheck.java'],classes,cp)
    agent=out/'check-agent.jar'
    with zipfile.ZipFile(agent,'w')as z:
        z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nPremain-Class: DeformedMotionCheck\nCan-Retransform-Classes: true\nCan-Redefine-Classes: true\n\n')
        for f in classes.rglob('*.class'):z.write(f,f.relative_to(classes).as_posix())
    command=[str(game/'jre64/bin/java.exe'),'--enable-native-access=ALL-UNNAMED','-Djava.io.tmpdir='+str(BUILD/'tmp'),'-javaagent:'+str(agent),'-cp',';'.join(map(str,[classes,*cp])),'DeformedMotionCheck',str(out.resolve()),str(a.before).lower()]
    r=subprocess.run(command,env=environment(),capture_output=True,text=True,encoding='utf8',timeout=90)
    (out/'run.log').write_text(r.stdout+r.stderr,'utf8')
    if r.returncode:print((r.stdout+r.stderr)[-4500:]);r.check_returncode()
    result=json.loads((out/'result.json').read_text('utf8'));result.update(jar_sha256=sha(a.jar),host_sha256=sha(c['viewpoint_jar']))
    save(out/'result.json',result);print(json.dumps(result,ensure_ascii=False))
