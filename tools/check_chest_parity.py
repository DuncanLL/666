"""Compare actual game FFM output with the approved C-ABI preview on identical poses."""
from pathlib import Path
import argparse,json,os,struct,sys
from common import ROOT,BUILD,settings,save
p=argparse.ArgumentParser();p.add_argument('--poses',type=Path,required=True);args=p.parse_args();c=settings()
sys.path[:0]=[c['python_dependencies'],str(ROOT/'experiments/softbody')]
import jpype,numpy as np
from softbody import SoftBody
game=Path(c['game_root']);temp=BUILD/'chest-parity';temp.mkdir(exist_ok=True);os.environ['TEMP']=os.environ['TMP']=str(temp);os.chdir(game)
jpype.startJVM(str(game/'jre64/bin/server/jvm.dll'),'--enable-native-access=ALL-UNNAMED','-Duser.home='+str(temp),'-Djava.io.tmpdir='+str(temp),classpath=[str(BUILD/'AimohPMXPreview.jar'),c['viewpoint_jar'],c['zombiebuddy_jar'],str(game),*map(str,game.glob('*.jar'))],convertStrings=True)
J=jpype.JClass;F=lambda v:jpype.JArray(jpype.JFloat)(v)
model=None;body=None
try:
 J('zombie.ZomboidFileSystem').instance.setCacheDir(str(temp));J('zombie.ZomboidFileSystem').instance.init()
 main=J('aimoh.pmxpreview.Main');field=main.class_.getDeclaredField('directory');field.setAccessible(True);field.set(None,J('java.nio.file.Path').of(str(Path(c['asset_root'])/'42')))
 init=main.class_.getDeclaredMethod('nativeRuntime');init.setAccessible(True);init.invoke(None);field=main.class_.getDeclaredField('api');field.setAccessible(True);api=field.get(None)
 rows=json.loads((BUILD/'chest-data/manifest.json').read_text('utf8'));chosen=max((r for r in rows if r['supported']),key=lambda r:r['bytes'])
 assets=Path(c['asset_root'])/'42/media/aimoh_pmx';registry=json.loads((assets/'registry.json').read_text('utf8'))['models'];entry=next(r for r in registry if r['id']==chosen['id']);profile=json.loads((assets/entry['profile']).read_text('utf8'))
 model=J('aimoh.motion.PmxModel').load(api,J('java.nio.file.Path').of(str(assets/entry['model'])),J('java.nio.file.Path').of(str(assets/entry['profile'])))
 data=(BUILD/'chest-data'/(profile['pmx_sha256']+'.bin')).read_bytes();offset=52;regions=[];body=SoftBody()
 for _ in chosen['bones']:
  values=struct.unpack_from('<IIf9f',data,offset);offset+=48;bone,count,mass=values[:3];arrays=[]
  for dtype,n in [('<u4',count),('<f4',count),('<f4',count*3),('<f4',count*3)]:arrays.append(np.frombuffer(data,dtype=dtype,count=n,offset=offset).copy());offset+=n*4
  ids,weights,rest,normals=arrays;rest=rest.reshape(-1,3);normals=normals.reshape(-1,3)
  body.add(values[3:6],values[6:9],values[9:12],rest,normals,mass=mass);regions.append((bone,ids,weights))
 n=profile['vertices'];palette=J('java.nio.ByteBuffer').allocateDirect(profile['bones']*64);pb=J('java.nio.ByteBuffer').allocateDirect(n*12);nb=J('java.nio.ByteBuffer').allocateDirect(n*12)
 scale=np.float32(1./np.float32(profile['unit_scale']));maximum=0.;normal_error=0.;affected=np.unique(np.concatenate([r[1]for r in regions]));poses=np.load(args.poses.resolve())['poses'][:240]
 for i,pose in enumerate(poses):
  model.apply(F(pose),None,1/60);assert not model.chestError()
  api.CopySkinningMatricesToBuffer(model.handle(),palette);matrices=np.frombuffer(memoryview(palette),dtype='<f4').reshape(-1,16)[[r[0]for r in regions]].copy();matrices[:,12:15]*=scale
  body.frames(matrices,reset=i==0);outputs=body.step(1/60)
  api.CopyDataToByteBuffer(pb,api.GetPoss(model.handle()),n*12);api.CopyDataToByteBuffer(nb,api.GetNormals(model.handle()),n*12)
  raw=np.frombuffer(memoryview(pb),dtype='<f4').copy().reshape(-1,3);normals=np.frombuffer(memoryview(nb),dtype='<f4').copy().reshape(-1,3)
  p=F(raw.ravel());normal=F(normals.ravel());model.deformChest(p,normal)
  for (_,ids,weight),(dp,dn)in zip(regions,outputs):raw[ids]+=weight[:,None]*dp/scale;normals[ids]+=weight[:,None]*dn
  normals[affected]/=np.maximum(np.linalg.norm(normals[affected],axis=1,keepdims=True),1e-8)
  maximum=max(maximum,float(abs(np.asarray(p).reshape(-1,3)-raw).max()));normal_error=max(normal_error,float(abs(np.asarray(normal).reshape(-1,3)-normals).max()))
 assert maximum<1e-4 and normal_error<1e-4,(maximum,normal_error)
 save(BUILD/'game-softbody/parity-check.json',dict(status='PASS_GAME_FFM_AND_PREVIEW_C_ABI_SAME_INPUT',model=chosen['id'],frames=len(poses),position_max_error_model_units=maximum,normal_max_error=normal_error,game_started=False,actual_gameplay_tested=False))
 print('FFM/preview parity:',maximum,normal_error,flush=True)
finally:
 if body:body.close()
 if model:model.close()
 jpype.shutdownJVM()
