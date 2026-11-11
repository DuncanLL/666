"""Replay capture pauses through actual ModelSession/AnimationPlayer/native physics."""
from pathlib import Path
import argparse,json,os,sys,time
from common import ROOT,BUILD,settings,save,sha

p=argparse.ArgumentParser(description=__doc__);p.add_argument('--source-config',type=Path,required=True);p.add_argument('--jar',type=Path,default=BUILD/'AimohPMXPreview.jar');p.add_argument('--before',action='store_true');a=p.parse_args();a.jar=a.jar.resolve();c=settings()
sys.path[:0]=[c['python_dependencies'],c['pmx_reader_scripts']]
from native_session import NativeSession
out=BUILD/'frame-timing'/('before'if a.before else'fixed');out.mkdir(parents=True,exist_ok=True)
cfg=json.loads(a.source_config.read_text('utf8'));game=Path(c['game_root']);cfg.update(game_dir=str(game),python_deps=c['python_dependencies'],skeleton_source=str(game/'media/models_X/Skinned/MaleBody.x'),override_roots=[],additional_jars=[str(a.jar.resolve()),c['viewpoint_jar'],c['zombiebuddy_jar'],*map(str,game.glob('*.jar'))])
os.environ['TEMP']=os.environ['TMP']=str(out)
s=NativeSession(cfg,out/('source-'+str(os.getpid())));J=s.J;model=None
try:
    os.chdir(game);fs=J('zombie.ZomboidFileSystem').instance;fs.setCacheDir(str(out/'User'));fs.init()
    main=J('aimoh.pmxpreview.Main')
    def field(cls,name):
        f=cls.getDeclaredField(name);f.setAccessible(True);return f
    field(main.class_,'directory').set(None,J('java.nio.file.Path').of(str(Path(c['asset_root'])/'42')))
    init=main.class_.getDeclaredMethod('nativeRuntime');init.setAccessible(True);init.invoke(None);api=field(main.class_,'api').get(None);grip=field(main.class_,'grip').get(None)
    tracks=s.tracks([dict(path=str(game/'media/anims_X/Bob/Bob_IdleNoAim1Hand.x'),loop=True)]);s.sample(tracks,.25,[1.])
    assets=Path(c['asset_root'])/'42/media/aimoh_pmx';rows=json.loads((assets/'registry.json').read_text('utf8'))['models'];row=next(r for r in rows if r['id']=='pmx-c7270af966e8aaf8')
    path=J('java.nio.file.Path').of;entry=J('aimoh.pmxpreview.Main$Entry')(row['id'],row['label'],path(str(assets/row['model'])),path(str(assets/row['profile'])))
    cls=J('aimoh.pmxpreview.ModelSession').class_;ctor=cls.getDeclaredConstructors()[0];ctor.setAccessible(True);session=ctor.newInstance(api,grip,entry);field(cls,'queued').setBoolean(session,True)
    model=field(cls,'model').get(session);assert model.physicsEnabled() and model.hasChestPhysics()
    update=next(m for m in cls.getDeclaredMethods()if str(m.getName())=='update'and m.getParameterCount()==9);update.setAccessible(True)
    frame=J('viewpoint.core.Frame')();frame.number=1;frame.simNanos=1_000_000_000
    def capture():update.invoke(session,frame,s.player,s.jpype.JBoolean(False),s.jpype.JFloat(0),s.jpype.JFloat(0),s.jpype.JFloat(0),s.jpype.JBoolean(False),s.jpype.JBoolean(False),s.jpype.JBoolean(False))
    capture();initial=model.retargeter().advancingPhysicsFrames();time.sleep(.04);frame.number=2;capture();paused=model.retargeter().advancingPhysicsFrames()
    frame.number=3;frame.simNanos+=16_666_667;capture();advanced=model.retargeter().advancingPhysicsFrames()
    if a.before:assert paused>initial,'Old wall-time advance during pause not reproduced'
    else:assert paused==initial and advanced==initial+1,(initial,paused,advanced)
    result=dict(status='OLD_WALL_TIME_PHYSICS_ADVANCE_DURING_SIMULATION_PAUSE_REPRODUCED'if a.before else'PASS_SIMULATION_CLOCK_NOT_RENDER_WALL_TIME',initial=initial,after_paused_capture=paused,after_one_simulation_step=advanced,chest_error=str(model.chestError()),jar_sha256=sha(a.jar),game_started=False,actual_gameplay_tested=False)
    save(out/'result.json',result);print(json.dumps(result),flush=True)
    model.close();model=None;grip.close()
finally:
    if model is not None:model.close()
    s.close()
