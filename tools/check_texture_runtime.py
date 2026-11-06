"""Exercise real STB allocation, GL uploads and release; never starts the game."""
from pathlib import Path
import argparse,json,subprocess
from common import ROOT,BUILD,settings,environment,save,sha
from build import compile_java

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar',type=Path,default=BUILD/'AimohPMXPreview.jar')
    parser.add_argument('--game-root',type=Path)
    parser.add_argument('--cycles',type=int,default=2)
    args=parser.parse_args();assert 1<=args.cycles<=10
    cfg=settings()
    if args.game_root:cfg['game_root']=str(args.game_root.resolve())
    game=Path(cfg['game_root']);out=BUILD/'texture-free-fix';out.mkdir(exist_ok=True)
    classpath=[args.jar.resolve(),Path(cfg['viewpoint_jar']),Path(cfg['zombiebuddy_jar']),*game.glob('*.jar')]
    classes=out/'classes'
    compile_java(cfg,[ROOT/'tools/java/TextureRuntimeCheck.java'],classes,classpath)
    command=[str(game/'jre64/bin/java.exe'),'--enable-native-access=ALL-UNNAMED',
             '--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED','-Djava.awt.headless=true','-Xmx768m',
             '-Dorg.lwjgl.util.DebugAllocator=true','-Dorg.lwjgl.util.DebugAllocator.internal=true',
             '-Djava.io.tmpdir='+str(BUILD/'tmp'),'-XX:ErrorFile='+str(out/'hs_err_pid%p.log'),
             '-cp',';'.join(map(str,[classes,*classpath])),'aimoh.pmxpreview.TextureRuntimeCheck',
             str(Path(cfg['game_install_roots'][0])/'42'),str(out/'User'),str(args.cycles)]
    log=out/'runtime.log'
    with log.open('w',encoding='utf8')as stream:
        result=subprocess.run(command,cwd=game,env=environment(),stdout=stream,stderr=subprocess.STDOUT,timeout=180)
    output=log.read_text('utf8',errors='replace')
    failures=['not being tracked','bytes leaked','Unhandled exception','EXCEPTION_ACCESS_VIOLATION']
    assert result.returncode==0 and 'PASS_TEXTURE_RUNTIME 'in output and not any(p in output for p in failures),output[-5000:]
    checked=[line.split('CHECK ',1)[1].split()for line in output.splitlines()if 'CHECK 'in line]
    report=dict(status='PASS_REAL_TEXTURE_DECODE_UPLOAD_RELEASE_DEBUG_ALLOCATOR',jar_sha256=sha(args.jar),
                game_root=str(game),cycles=args.cycles,models=sorted({r[1]for r in checked}),
                completed_model_sessions=len(checked),invalid_free_or_allocator_leaks_detected=False,
                unchanged_texture_reuse_checked=True,remaining_native_objects=0,remaining_chest_worlds=0,
                private_hidden_gl_context=True,game_started=False,actual_gameplay_tested=False,log=str(log))
    save(out/'runtime-check.json',report);print(json.dumps(report,ensure_ascii=False))
