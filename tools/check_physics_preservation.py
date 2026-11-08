"""Compare old/new geometry and authored/chest physics on identical recorded game poses."""
from pathlib import Path
import argparse,json,os,subprocess,sys,zipfile
from common import ROOT,BUILD,settings,save,sha,environment

p=argparse.ArgumentParser(description=__doc__);p.add_argument('--baseline',type=Path);p.add_argument('--jar',type=Path);p.add_argument('--old',action='store_true');a=p.parse_args();c=settings()
out=BUILD/'lifecycle-0.3.6/physics';out.mkdir(parents=True,exist_ok=True)
sys.path.insert(0,c['python_dependencies'])
import numpy as np
if a.jar is None:
    assert a.baseline
    for label,jar in [('old',a.baseline),('new',BUILD/'AimohPMXPreview.jar')]:
        command=[sys.executable,__file__,'--jar',str(jar.resolve())]+(['--old']if label=='old'else[])
        with (out/(label+'.log')).open('w',encoding='utf8')as log:
            subprocess.run(command,cwd=ROOT,env=environment(),stdout=log,stderr=subprocess.STDOUT,check=True,timeout=180)
    old=np.load(out/'old.npz');new=np.load(out/'new.npz');errors={}
    assert old.files==new.files
    for key in old.files:
        error=float(np.max(np.abs(old[key]-new[key])));assert error<1e-5,(key,error);errors[key]=error
    with zipfile.ZipFile(a.baseline)as b,zipfile.ZipFile(BUILD/'AimohPMXPreview.jar')as n:
        payload=[key for key in b.namelist()if not key.endswith('.class')];assert set(payload)=={key for key in n.namelist()if not key.endswith('.class')}
        assert all(b.read(key)==n.read(key)for key in payload)
    result=dict(status='PASS_OLD_NEW_RECORDED_POSES_AND_PHYSICS_PARITY',models=3,frames_per_model=120,position_and_normal_max_errors=errors,
                native_and_binding_payloads_byte_identical=True,old_late_setting_partial_commit_reproduced=True,new_invalid_setting_atomic=True,
                baseline_sha256=sha(a.baseline),candidate_sha256=sha(BUILD/'AimohPMXPreview.jar'),game_started=False,actual_gameplay_tested=False)
    save(out/'result.json',result);print(json.dumps(result));raise SystemExit

import jpype
game=Path(c['game_root']);label='old'if a.old else'new';temp=out/label;temp.mkdir(exist_ok=True)
os.environ['TEMP']=os.environ['TMP']=str(temp);os.chdir(game)
jpype.startJVM(str(game/'jre64/bin/server/jvm.dll'),'--enable-native-access=ALL-UNNAMED','-Djava.io.tmpdir='+str(temp),
              classpath=[str(a.jar.resolve()),c['viewpoint_jar'],c['zombiebuddy_jar'],str(game),*map(str,game.glob('*.jar'))],convertStrings=True)
J=jpype.JClass;F=lambda values:jpype.JArray(jpype.JFloat)(values);path=J('java.nio.file.Path').of;Q=J('org.json.JSONObject')
try:
    fs=J('zombie.ZomboidFileSystem').instance;fs.setCacheDir(str(temp/'User'));fs.init();main=J('aimoh.pmxpreview.Main')
    def field(name):
        f=main.class_.getDeclaredField(name);f.setAccessible(True);return f
    field('directory').set(None,path(str(Path(c['asset_root'])/'42')));init=main.class_.getDeclaredMethod('nativeRuntime');init.setAccessible(True);init.invoke(None);api=field('api').get(None)
    assets=Path(c['asset_root'])/'42/media/aimoh_pmx';rows=json.loads((assets/'registry.json').read_text('utf8'))['models']
    supported=[r['id']for r in json.loads((BUILD/'chest-data/manifest.json').read_text('utf8'))if r['supported']]
    chosen=[supported[0],supported[len(supported)//2],supported[-1]]
    clips=[np.load(BUILD/'softbody'/name)['poses'][:60]for name in ['source-poses.npz','dance-poses.npz']];poses=np.concatenate(clips)
    assert len(poses)==120
    samples={}
    for index,id in enumerate(chosen):
        row=next(r for r in rows if r['id']==id);profile=json.loads((assets/row['profile']).read_text('utf8'))
        model=J('aimoh.motion.PmxModel').load(api,path(str(assets/row['model'])),path(str(assets/row['profile'])))
        try:
            n=profile['vertices'];pb=J('java.nio.ByteBuffer').allocateDirect(n*12);nb=J('java.nio.ByteBuffer').allocateDirect(n*12);captured=[]
            appearance=str(model.appearance().stateJson());chest=str(model.chestSettings())
            config=dict(appearance=json.loads(appearance),appearance_controls=profile.get('appearance_controls',{}),chest_physics=json.loads(chest))
            for frame,pose in enumerate(poses):
                if frame in [20,40,80]:
                    config['chest_physics']['inertia']=min(100,config['chest_physics']['inertia']+5)if frame==40 else json.loads(chest)['inertia']
                    model.applySettings(json.dumps(config))
                model.apply(F(pose),None,1/60);assert not model.chestError()
                if frame%15==0:
                    api.CopyDataToByteBuffer(pb,api.GetPoss(model.handle()),n*12);api.CopyDataToByteBuffer(nb,api.GetNormals(model.handle()),n*12)
                    points=F(np.frombuffer(memoryview(pb),dtype='<f4').copy());normals=F(np.frombuffer(memoryview(nb),dtype='<f4').copy());model.deformChest(points,normals)
                    captured.append(np.stack([np.asarray(points).copy(),np.asarray(normals).copy()]))
            previous=json.loads(str(model.appearance().stateJson()));bad=json.loads(json.dumps(config));bad['appearance']['material_visibility'][0]=not previous['material_visibility'][0];bad['chest_physics']['strength']=101
            try:model.applySettings(json.dumps(bad));raise AssertionError('Invalid late setting accepted')
            except jpype.JException:pass
            partial=json.loads(str(model.appearance().stateJson()))!=previous
            assert partial==a.old,(label,'late-setting rollback',partial)
            samples['model_'+str(index)]=np.asarray(captured);print(label,id,'120 frames; late-setting partial commit',partial,flush=True)
        finally:model.close()
    assert J('aimoh.motion.ChestSoftBody').liveWorlds()==0
    assert all(v==0 for v in json.loads(str(api.GetRuntimeStats())).values());field('grip').get(None).close()
    np.savez(out/(label+'.npz'),**samples)
finally:jpype.shutdownJVM()
