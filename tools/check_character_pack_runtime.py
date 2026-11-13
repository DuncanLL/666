"""Exercise registration and catalog merging against the game JVM and staged character packs."""
from pathlib import Path
import json,os,sys,shutil,hashlib
from common import ROOT,BUILD,settings
c=settings()
sys.path.insert(0,c['python_dependencies'])
import jpype
game=Path(c['game_root'])
mode=sys.argv[1]
root=Path(sys.argv[2]).resolve(strict=True)/'Contents/mods/AimohPmxPreview'
jar=root/'42/media/java/client/AimohPMXPreview.jar'
reg=json.loads((root/'42/media/aimoh_pmx/registry.json').read_text('utf8'))
assets=Path(sys.argv[3]).resolve(strict=True)
game_jars=[c['zombiebuddy_jar'],str(game/'projectzomboid.jar')]
host=Path(sys.argv[4]).resolve(strict=True) if len(sys.argv)>4 else Path(c['viewpoint_jar'])
os.chdir(game)
jpype.startJVM(str(game/'jre64/bin/server/jvm.dll'),'--enable-native-access=ALL-UNNAMED',classpath=[str(jar),str(host),*game_jars],convertStrings=True)
J=jpype.JClass
try:
 fs=J('zombie.ZomboidFileSystem').instance
 files=fs.activeFileMap
 mods=fs.getModIDs()
 runtime=J('aimoh.pmxpreview.Main')
 room=J('aimoh.pmxpreview.ReadyRoomPreview')
 fs.setModIdToDir('AimohPmxPreview',str(root))
 for mod,ids in reg['packs'].items():
  # Simulate the stale metadata that used to hide all packs, while the active map
  # still points at the game-selected version.
  fs.setModIdToDir(mod,str(BUILD/'stale-mod-root'/mod))
  rel=f'media/aimoh_pmx/packs/{mod}.json'
  active=assets/mod/'Contents/mods'/mod/'42'/rel
  assert active.is_file()
  files.put(rel.lower(),str(active))
 assert runtime.modelCount()==0
 mods.add('R116Laiya')
 runtime.clear()
 assert runtime.modelCount()==len(reg['packs']['R116Laiya'])
 for mod in reg['packs']:
  if mod!='R116Laiya': mods.add(mod)
 runtime.clear()
 expected={ident for values in reg['packs'].values() for ident in values}
 assert runtime.modelCount()==len(expected)==60
 assert {runtime.modelIdAt(i) for i in range(runtime.modelCount())}==expected
 models=room.data().rawget('models')
 catalog=json.loads((root/'42/media/ui/AimohPMXReadyRoom/catalog.json').read_text('utf8'))
 for mod,ids in reg['packs'].items():
  for ident in ids:
   if ident in catalog['models']:
    assert models.rawget(ident).rawget('pack')==mod,(mod,ident)
 # A stale role release with fewer registered outfits remains usable, while an
 # unknown ID is ignored. File paths for shared models are the real assets.
 role='R116Laiya'
 rel=f'media/aimoh_pmx/packs/{role}.json'
 current=Path(str(files.get(rel.lower())))
 fixture=BUILD/f'old-pack-{mode}'/'media/aimoh_pmx'
 (fixture/'packs').mkdir(parents=True,exist_ok=True)
 data=json.loads(current.read_text('utf8'))
 known=next(m for m in data['models'] if not m.get('hidden',False))
 for key in ('model','profile'):
  src=current.parent.parent/known[key]
  dst=fixture/known[key]
  dst.parent.mkdir(parents=True,exist_ok=True)
  shutil.copyfile(src,dst)
 older=fixture/'packs'/current.name
 data['models']=[known,dict(id='pmx-unknown-model',label='future outfit',model='absent',profile='absent')]
 older.write_text(json.dumps(data,ensure_ascii=False),'utf8')
 files.put(rel.lower(),str(older))
 mods.clear();mods.add(role)
 runtime.clear()
 assert runtime.modelCount()==1 and runtime.modelIdAt(0)==known['id']
 assert runtime.modelListed(0)
 mods.clear()
 runtime.clear()
 assert runtime.modelCount()==0
 result=dict(status='PASS',variant=mode,installed_packs=22,registered_models=60,cold_disabled=0,
             stale_mod_directory='active game file resolution',older_pack_partial_match=True,
             world_switch=True,game_started=False,jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest())
 (BUILD/f'{mode}-pack-check.json').write_text(json.dumps(result,indent=2)+'\n','utf8')
 print(mode,result,flush=True)
finally:jpype.shutdownJVM()
