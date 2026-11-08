"""Validate packaged Workshop metadata and normal auto-install using a private user root."""
from pathlib import Path
import argparse,json,os,subprocess,zipfile
from common import ROOT,BUILD,settings,sha,save,environment
from build import compile_java

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--project',type=Path,default=BUILD/'workshop/R116RenderSupport');a=p.parse_args()
    cfg=settings();project=a.project.resolve();mod=project/'Contents/mods/AimohGF2Eclipse'
    jar=mod/'42/media/java/client/AimohGF2Eclipse.jar';game=Path(cfg['game_root']);host=Path(cfg['viewpoint_jar'])
    origin=json.loads((ROOT/'provenance/render-support.json').read_text('utf8'))
    before=sha(host);assert before==origin['official_host_sha256']
    with zipfile.ZipFile(host)as z:stock=set(z.namelist())
    with zipfile.ZipFile(jar)as z:
        assert not [n for n in z.namelist()if n.endswith('.class')and n in stock]
        payload=z.read('aimoh/gf2/bridge/Aimoh-Native-GF2.zip');assert 'aimoh/gf2/bridge/Aimoh-Eclipse-GF2.zip'not in z.namelist()
    import hashlib
    build=json.loads((BUILD/'render-support/build.json').read_text('utf8'))
    assert hashlib.sha256(payload).hexdigest()==build['shader_sha256']
    out=BUILD/'render-support/checks';out.mkdir(parents=True,exist_ok=True)
    agent=out/'test-agent.jar'
    compile_java(cfg,[ROOT/'tools/java/RenderSupportTestAgent.java'],out/'agent-classes',[jar,host,cfg['zombiebuddy_jar'],*game.glob('*.jar')])
    with zipfile.ZipFile(agent,'w',zipfile.ZIP_DEFLATED)as z:
        z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nPremain-Class: RenderSupportTestAgent\nCan-Retransform-Classes: true\n\n')
        z.write(out/'agent-classes/RenderSupportTestAgent.class','RenderSupportTestAgent.class')
    cp=[jar,Path(cfg['reference_jar']),host,cfg['zombiebuddy_jar'],agent,*game.glob('*.jar')]
    compile_java(cfg,[ROOT/'tools/java/RenderSupportStartupCheck.java'],out/'classes',cp)
    command=[str(game/'jre64/bin/java.exe'),'-ea','-Xverify:all','--enable-native-access=ALL-UNNAMED',
             '-Duser.home='+str(out/'home'),'-Djava.io.tmpdir='+str(BUILD/'tmp'),
             '-javaagent:'+str(agent)+'='+str(jar),'-cp',
             ';'.join(map(str,[out/'classes',*cp])),'RenderSupportStartupCheck',str(out)]
    result=subprocess.run(command,env=environment(),cwd=game,capture_output=True,text=True,encoding='utf8',errors='replace',timeout=90)
    (out/'startup.log').write_text(result.stdout+result.stderr,'utf8');result.check_returncode()
    assert 'NORMAL_NATIVE_EMBEDDED_INSTALL_DISCOVERY_PASS'in result.stdout
    assert sha(host)==before,'Official VP modified'
    compile_java(cfg,[ROOT/'tools/java/RelocatedResourceChecks.java'],out/'resource-classes',cp)
    resource_command=[str(game/'jre64/bin/java.exe'),'-ea','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-cp',';'.join(map(str,[out/'resource-classes',*cp])),'RelocatedResourceChecks',str(Path(cfg['asset_root'])/'42/media'),str(ROOT/'render-support/native-pack'),str(out/'resources.json')]
    resource_result=subprocess.run(resource_command,env=environment(),cwd=game,capture_output=True,timeout=90)
    (out/'resources.log').write_bytes(resource_result.stdout+resource_result.stderr);resource_result.check_returncode()
    # Reuse the game's actual Workshop validator, without starting the game.
    import sys
    sys.path.insert(0,cfg['python_dependencies']);import jpype
    jpype.startJVM(str(game/'jre64/bin/server/jvm.dll'),'--enable-native-access=ALL-UNNAMED','-Duser.home='+str(out/'validator-home'),
                 '-Djava.io.tmpdir='+str(BUILD/'tmp'),classpath=[str(game),*map(str,game.glob('*.jar'))],convertStrings=True)
    try:
        os.chdir(game)
        J=jpype.JClass;J('zombie.ZomboidFileSystem').instance.setCacheDir(str(out/'validator-home'));J('zombie.ZomboidFileSystem').instance.init()
        item=J('zombie.core.znet.SteamWorkshopItem')(str(project));assert item.readWorkshopTxt()
        assert item.validateContents()is None,str(item.validateContents())
        assert item.getTitle()=='R116 渲染支持 光影分发'and item.getDescription()=='\u200b'
    finally:jpype.shutdownJVM()
    report=json.loads((out/'startup.json').read_text('utf8'));report.update(workshop_validator_passed=True,
           jar_sha256=sha(jar),shader_sha256=build['shader_sha256'],official_host_unchanged=True,uploaded=False)
    save(out/'validation.json',report);print(json.dumps(report,ensure_ascii=False))
