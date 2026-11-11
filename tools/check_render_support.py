"""Validate packaged Workshop metadata and normal auto-install using a private user root."""
from pathlib import Path
import argparse,hashlib,json,os,subprocess,zipfile
from common import ROOT,BUILD,settings,sha,save,environment
from build import compile_java

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--project',type=Path,default=BUILD/'workshop/R116RenderSupport');p.add_argument('--backend',choices=['native','eclipse','combined'],default='combined');p.add_argument('--protected-report',type=Path);a=p.parse_args()
    eclipse=a.backend=='eclipse'
    combined=a.backend=='combined'
    build_dir=BUILD/({'native':'render-support','eclipse':'eclipse-support','combined':'combined-support'}[a.backend])
    build=json.loads((build_dir/'build.json').read_text('utf8'))
    backends=build['backends'] if combined else {a.backend:build}
    if a.protected_report:
        assert combined, 'Protected review builds contain both backends'
        protected=json.loads(a.protected_report.read_text('utf8'))
        assert protected['status']=='PROTECTED_REVIEW_BUILD' and protected['input_jar_sha256']==build['jar_sha256']
        assert protected['host_sha256']==build['host_sha256'] and set(protected['shaders'])==set(backends)
        backends={name:dict(row,shader_sha256=protected['shaders'][name]['sha256']) for name,row in backends.items()}
        build['jar_sha256']=protected['jar_sha256']
    cfg=settings();project=a.project.resolve();mod=project/'Contents/mods/AimohGF2Eclipse'
    jar=mod/'42/media/java/client/AimohGF2Eclipse.jar';game=Path(cfg['game_root']);host=Path(cfg['viewpoint_jar'])
    before=sha(host);assert before==build['host_sha256']
    with zipfile.ZipFile(host)as z:stock=set(z.namelist())
    with zipfile.ZipFile(jar)as z:
        assert not [n for n in z.namelist()if n.endswith('.class')and n in stock]
        payloads={f'aimoh/gf2/bridge/Aimoh-{name.capitalize()}-GF2.zip' for name in backends}
        assert {name for name in z.namelist() if name.startswith('aimoh/gf2/bridge/Aimoh-') and name.endswith('-GF2.zip')}==payloads
        for row in backends.values():
            assert hashlib.sha256(z.read(row['shader_payload'])).hexdigest()==row['shader_sha256']
        if a.protected_report:
            resource=protected.get('sealed_resource','aimoh/gf2/bridge/render-inputs.bin')
            assert hashlib.sha256(z.read(resource)).hexdigest()==protected['sealed_payload_sha256']
        if not eclipse:
            display=z.read('aimoh/gf2/bridge/gf2-original-display.rgba16f')
            expected_display=json.loads((ROOT/'resources/manifest.json').read_text('utf8'))['render_support']['original_display_profile']
            assert len(display)==expected_display['bytes']and hashlib.sha256(display).hexdigest()==expected_display['sha256']
    assert sha(jar)==build['jar_sha256'],'Workshop JAR is not the build being validated'
    out=(a.protected_report.resolve().parent if a.protected_report else build_dir)/'checks';out.mkdir(parents=True,exist_ok=True)
    agent=out/'test-agent.jar'
    compile_java(cfg,[ROOT/'tools/java/RenderSupportTestAgent.java'],out/'agent-classes',[jar,host,cfg['zombiebuddy_jar'],*game.glob('*.jar')])
    with zipfile.ZipFile(agent,'w',zipfile.ZIP_DEFLATED)as z:
        z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nPremain-Class: RenderSupportTestAgent\nCan-Retransform-Classes: true\n\n')
        z.write(out/'agent-classes/RenderSupportTestAgent.class','RenderSupportTestAgent.class')
    cp=[jar,Path(cfg['reference_jar']),host,cfg['zombiebuddy_jar'],agent,*game.glob('*.jar')]
    if not eclipse and not a.protected_report:
        compile_java(cfg,[ROOT/'tools/java/NativePackOptionsCheck.java'],out/'pack-options-classes',cp)
        options_check=subprocess.run([str(game/'jre64/bin/java.exe'),'-ea','-cp',';'.join(map(str,[out/'pack-options-classes',*cp])),'viewpoint.render.NativePackOptionsCheck'],env=environment(),capture_output=True,text=True,timeout=30)
        (out/'pack-options.log').write_text(options_check.stdout+options_check.stderr,'utf8');options_check.check_returncode()
        assert 'NATIVE_PACK_OPTIONS_COMPOSITION_PASS' in options_check.stdout
    if not a.protected_report:
        compile_java(cfg,[ROOT/'tools/java/OutlineBridgeCheck.java'],out/'outline-classes',cp)
        outline_check=subprocess.run([str(game/'jre64/bin/java.exe'),'-ea','-cp',';'.join(map(str,[out/'outline-classes',*cp])),'viewpoint.render.OutlineBridgeCheck'],env=environment(),capture_output=True,text=True,timeout=30)
        (out/'outline.log').write_text(outline_check.stdout+outline_check.stderr,'utf8');outline_check.check_returncode()
        assert 'SHARED_OUTLINE_PARITY_CHECK_PASS' in outline_check.stdout
    compile_java(cfg,[ROOT/'tools/java/RenderSupportStartupCheck.java'],out/'classes',cp)
    command=[str(game/'jre64/bin/java.exe'),'-ea','-Xverify:all','--enable-native-access=ALL-UNNAMED',
             '-Duser.home='+str(out/'home'),'-Djava.io.tmpdir='+str(BUILD/'tmp'),
             '-javaagent:'+str(agent)+'='+str(jar),'-cp',
             ';'.join(map(str,[out/'classes',*cp])),'RenderSupportStartupCheck',str(out),a.backend]
    if a.protected_report:
        command.extend(('sealed',protected.get('marker_prefix','/*r116$'),protected.get('dispatcher_class','K')))
    result=subprocess.run(command,env=environment(),cwd=game,capture_output=True,text=True,encoding='utf8',errors='replace',timeout=90)
    (out/'startup.log').write_text(result.stdout+result.stderr,'utf8')
    if result.returncode:print((result.stdout+result.stderr)[-7000:],flush=True)
    result.check_returncode()
    assert f'NORMAL_{a.backend.upper()}_EMBEDDED_INSTALL_DISCOVERY_PASS'in result.stdout
    assert sha(host)==before,'Official VP modified'
    if not eclipse:
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
        if project.parent.name.casefold()=='workshop':
            J('zombie.ZomboidFileSystem').instance.setCacheDir(str(project.parent.parent))
        item=J('zombie.core.znet.SteamWorkshopItem')(str(project));assert item.readWorkshopTxt()
        assert item.validateContents()is None,str(item.validateContents())
        assert item.getTitle()=='R116 渲染支持 光影分发'and item.getDescription()=='\u200b'
    finally:jpype.shutdownJVM()
    report=json.loads((out/'startup.json').read_text('utf8'));report.update(workshop_validator_passed=True,
           jar_sha256=sha(jar),official_host_unchanged=True,uploaded=False,
           validation_scope='STARTUP_AND_RESOURCE_DISCOVERY_ONLY',cross_vendor_status='NOT_ESTABLISHED_BY_THIS_CHECK')
    if combined:report['shader_hashes']={name:row['shader_sha256'] for name,row in backends.items()}
    else:report['shader_sha256']=build['shader_sha256']
    save(out/'validation.json',report);print(json.dumps(report,ensure_ascii=False))
