"""Actual packaged Java/native/PMX chain, without starting or controlling the game."""
from pathlib import Path
import argparse,json,os,sys,time
from common import ROOT,BUILD,settings,save,sha
p=argparse.ArgumentParser();p.add_argument('--poses',type=Path,required=True);args=p.parse_args()
args.poses=args.poses.resolve()
c=settings();sys.path.insert(0,c['python_dependencies'])
import jpype,numpy as np
game=Path(c['game_root']);home=BUILD/'chest-runtime-check';home.mkdir(exist_ok=True)
os.environ['TEMP']=os.environ['TMP']=str(home)
classpath=[str(BUILD/'AimohPMXPreview.jar'),c['zombiebuddy_jar'],c['viewpoint_jar'],str(game),*map(str,game.glob('*.jar'))]
jpype.startJVM(str(game/'jre64/bin/server/jvm.dll'),'--enable-native-access=ALL-UNNAMED','-Djava.io.tmpdir='+str(home),'-Duser.home='+str(home),classpath=classpath,convertStrings=True)
J=jpype.JClass;F=lambda a:jpype.JArray(jpype.JFloat)(a)
report=[]
try:
 os.chdir(game);J('zombie.ZomboidFileSystem').instance.setCacheDir(str(home));J('zombie.ZomboidFileSystem').instance.init()
 main=J('aimoh.pmxpreview.Main');field=main.class_.getDeclaredField('directory');field.setAccessible(True)
 field.set(None,J('java.nio.file.Path').of(str(Path(c['asset_root'])/'42')))
 init=main.class_.getDeclaredMethod('nativeRuntime');init.setAccessible(True);init.invoke(None)
 field=main.class_.getDeclaredField('api');field.setAccessible(True);api=field.get(None)
 assert api.GetVersion()=='Aimoh-Upstream-1/skeleton-eval1-softbody1'
 chest=J('aimoh.motion.ChestSoftBody');model_class=J('aimoh.motion.PmxModel');json_class=J('org.json.JSONObject')
 assets=Path(c['asset_root'])/'42/media/aimoh_pmx'
 registry=json.loads((ROOT/'game/mod/42/media/aimoh_pmx/registry.json').read_text('utf8'))['models']
 supported={r['id'] for r in json.loads((BUILD/'chest-data/manifest.json').read_text('utf8'))if r['supported']}
 poses=np.load(args.poses.resolve())['poses'][:420]
 for row in registry:
  profile=json.loads((assets/row['profile']).read_text('utf8'))
  model=model_class.load(api,J('java.nio.file.Path').of(str(assets/row['model'])),J('java.nio.file.Path').of(str(assets/row['profile'])))
  try:
   assert model.hasChestPhysics()==(row['id']in supported)
   peak=0.;seconds=[];n=int(profile['vertices']);pb=J('java.nio.ByteBuffer').allocateDirect(n*12);nb=J('java.nio.ByteBuffer').allocateDirect(n*12)
   for i,pose in enumerate(poses if row['id']in supported else poses[:3]):
    start=time.perf_counter();model.apply(F(pose),None,1/60)
    if i%10==0:
     api.CopyDataToByteBuffer(pb,api.GetPoss(model.handle()),n*12);api.CopyDataToByteBuffer(nb,api.GetNormals(model.handle()),n*12)
     raw=np.frombuffer(memoryview(pb),dtype='<f4').copy();normal=np.frombuffer(memoryview(nb),dtype='<f4').copy();p=F(raw);normals=F(normal)
     model.deformChest(p,normals)
     result=np.asarray(p);assert np.isfinite(result).all() and np.isfinite(np.asarray(normals)).all()
     peak=max(peak,float(abs(result-raw).max()))
    assert not model.chestError(),(row['label'],i,model.chestError())
    seconds.append((time.perf_counter()-start)*1000)
   if model.hasChestPhysics():
    assert peak>1e-4,(row['label'],peak)
    saved=str(model.chestSettings());model.chestSlider(2,80.);assert model.chestSlider(2)==80.
    state=json_class().put('appearance',json_class(model.appearance().stateJson())).put('appearance_controls',json_class(json.dumps(profile.get('appearance_controls',{'format':1,'groups':[]})))).put('chest_physics',json_class(saved))
    model.applySettings(str(state));assert str(model.chestSettings())==saved
    before=model.handle();model.resetChestSettings();assert model.handle()==before
    model.setPhysicsEnabled(False);model.apply(F(poses[30]),None,1/60)
    api.CopyDataToByteBuffer(pb,api.GetPoss(model.handle()),n*12);api.CopyDataToByteBuffer(nb,api.GetNormals(model.handle()),n*12)
    raw=np.frombuffer(memoryview(pb),dtype='<f4').copy();p=F(raw);normal=F(np.frombuffer(memoryview(nb),dtype='<f4').copy());model.deformChest(p,normal)
    assert np.array_equal(np.asarray(p),raw)
    model.setPhysicsEnabled(True);model.resetPhysics();model.apply(F(poses[31]),None,1/60);assert not model.chestError()
   assert sha(assets/row['model'])==profile['pmx_sha256']
   report.append(dict(id=row['id'],label=row['label'],chest=model.hasChestPhysics(),peak_deformation_model_units=peak,component_median_ms=float(np.median(seconds)),settings_restored=model.hasChestPhysics(),source_model_unchanged=True))
   print(json.dumps(report[-1],ensure_ascii=False),flush=True)
  finally:model.close()
  assert chest.liveWorlds()==0,'Leaked chest world'
 stats=json.loads(str(api.GetRuntimeStats()));assert all(v==0 for v in stats.values()),stats
 field=main.class_.getDeclaredField('grip');field.setAccessible(True);field.get(None).close()
 save(BUILD/'game-softbody/runtime-check.json',dict(status='PASS_PACKAGED_JAVA_NATIVE_MODEL_COMPONENT_CHAIN',models=report,remaining_objects=stats,remaining_chest_worlds=chest.liveWorlds(),game_started=False,actual_gameplay_tested=False,uploaded=False))
finally:jpype.shutdownJVM()
