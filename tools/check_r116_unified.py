"""Exercise the packaged R116 entry with the official ZombieBuddy and Viewpoint."""
import argparse
import json
from pathlib import Path
import subprocess
import zipfile

from build import compile_java
from common import ROOT, environment, save, settings, sha
from package_r116_unified import CHARACTER_JAR, CHARACTER_MOD


def check(project, legacy):
    config = settings()
    game = Path(config['game_root'])
    project = project.resolve(strict=True)
    jar = project / CHARACTER_MOD / CHARACTER_JAR
    out = project.parent.parent
    report = json.loads((out/'build.json').read_text('utf8'))
    assert sha(jar) == report['jar_sha256']
    deps = [Path(config['viewpoint_jar']), Path(config['zombiebuddy_jar']), *game.glob('*.jar')]
    assert sha(deps[0]) == report['host_sha256'] and sha(deps[0].with_suffix('.jar.zbs')) == report['signature_sha256']
    assert sha(deps[1]) == report['loader_sha256']
    classes = out/'check-classes'
    compile_java(config,[ROOT/'tools/java'/name for name in ('UnifiedStartupCheck.java','RenderSupportTestAgent.java')],classes,[jar,*deps])
    agent = out/'check-agent.jar'
    with zipfile.ZipFile(agent,'w',zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nPremain-Class: RenderSupportTestAgent\nCan-Retransform-Classes: true\nCan-Redefine-Classes: true\n\n')
        archive.write(classes/'RenderSupportTestAgent.class','RenderSupportTestAgent.class')
    cases = {'fresh': [jar]}
    if legacy:
        legacy = legacy.resolve(strict=True)
        cases.update({'unified-first':[jar,legacy],'legacy-first':[legacy,jar]})
    results = {}
    for name, jars in cases.items():
        case = out/'checks'/name
        case.mkdir(parents=True,exist_ok=True)
        cmd = [str(game/'jre64/bin/java.exe'),'-ea','-Xverify:all','--enable-native-access=ALL-UNNAMED',
               '--add-opens=java.base/java.lang=ALL-UNNAMED','-Duser.home='+str(case/'home'),
               '-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-javaagent:'+str(agent)+'='+str(jar),
               '-cp',';'.join(map(str,[classes,*jars,*deps])),'UnifiedStartupCheck',str(case),str(project),
               'fresh' if name=='fresh' else 'legacy']
        result = subprocess.run(cmd,env=environment(),cwd=game,capture_output=True,encoding='utf8',errors='replace',timeout=120)
        (case/'startup.log').write_text(result.stdout+result.stderr,'utf8')
        if result.returncode:
            print((result.stdout+result.stderr)[-5000:])
            result.check_returncode()
        results[name]=json.loads((case/'startup.json').read_text('utf8'))
    save(out/'startup-check.json',{'status':'UNIFIED_WORKSHOP_STARTUP_CHECKED','jar_sha256':sha(jar),'cases':results})
    print(json.dumps(results,ensure_ascii=False))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project',required=True,type=Path)
    parser.add_argument('--legacy-render',type=Path)
    args=parser.parse_args()
    check(args.project,args.legacy_render)
