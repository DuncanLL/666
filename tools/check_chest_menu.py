"""Run production Lua and the game's slider code in real Kahlua; widget/actor substitutes only."""
from pathlib import Path
import argparse,json,os,sys
from common import ROOT,BUILD,settings,save
p=argparse.ArgumentParser();p.add_argument('--hit-test',action='store_true');p.add_argument('--lifecycle',action='store_true');args=p.parse_args()
c=settings();sys.path.insert(0,c['python_dependencies'])
import jpype
game=Path(c['game_root']);temp=BUILD/'menu-check';temp.mkdir(exist_ok=True)
os.environ['TEMP']=os.environ['TMP']=str(temp)
os.chdir(game)
jpype.startJVM(str(game/'jre64/bin/server/jvm.dll'),'--enable-native-access=ALL-UNNAMED','-Djava.io.tmpdir='+str(temp),classpath=[str(game/'projectzomboid.jar')],convertStrings=True)
J=jpype.JClass;platform=J('se.krka.kahlua.j2se.J2SEPlatform').getInstance();env=platform.newEnvironment();thread=J('se.krka.kahlua.vm.KahluaThread')(platform,env);thread.debugOwnerThread=J('java.lang.Thread').currentThread()
def load(path):
 reader=J('java.io.InputStreamReader')(J('java.io.FileInputStream')(str(path)),'UTF-8')
 try:
  closure=J('se.krka.kahlua.luaj.compiler.LuaCompiler').loadis(reader,path.name,env)
  result=thread.pcall(closure,jpype.JArray(J('java.lang.Object'))(0))
  if not result[0]:raise AssertionError([str(v)for v in result])
  return result[1]if len(result)>1 else None
 finally:reader.close()
def table(value):
 if isinstance(value,(dict,list)):
  t=platform.newTable()
  for k,v in (value.items()if isinstance(value,dict)else enumerate(value,1)):t.rawset(float(k)if isinstance(k,int)else k,table(v))
  return t
 if isinstance(value,bool):return J('java.lang.Boolean')(value)
 if isinstance(value,(int,float)):return J('java.lang.Double')(value)
 return value
try:
 if args.hit_test:
  element=J('zombie.ui.UIElement');parent=element();button=element();preview=element()
  parent.AddChild(button);parent.AddChild(preview);preview.backMost()
  assert parent.getControls().get(1)==preview,'Recheck native backMost semantics before changing the fixture'
  parent.RemoveChild(preview);parent.RemoveChild(button);parent.AddChild(preview);parent.AddChild(button)
  assert parent.getControls().get(1)==button,'Native child insertion order changed'
 load(ROOT/'tools/tests/ui_fixture.lua');load(ROOT/'tools/tests/room_fixture.lua')
 catalog=json.loads((ROOT/'game/mod/42/media/ui/AimohPMXReadyRoom/catalog.json').read_text('utf8'));env.rawset('roomData',table(catalog))
 assets=Path(c['asset_root'])/'42/media/aimoh_pmx';registry=json.loads((assets/'registry.json').read_text('utf8'))['models']
 supported={r['id']for r in json.loads((BUILD/'chest-data/manifest.json').read_text('utf8'))if r['supported']}
 for row in registry:row['groups']=json.loads((assets/row['profile']).read_text('utf8')).get('appearance_controls',{}).get('groups',[]);row['chest']=row['id']in supported
 env.rawset('registryModels',table(registry));env.rawset('chestModelId','pmx-782f06826adb1307')
 load(ROOT/'tools/tests/chest_fixture.lua')
 if args.hit_test:
  load(ROOT/'tools/tests/room_hit_fixture.lua');load(game/'media/lua/client/ISUI/ISButton.lua')
 load(game/'media/lua/client/RadioCom/ISUIRadio/ISSliderPanel.lua')
 load(ROOT/'game/mod/42/media/lua/client/AimohPMXWindow.lua');load(ROOT/'game/mod/42/media/lua/client/AimohPMXPreview.lua')
 status=str(load(ROOT/'tools/tests'/('lifecycle_assertions.lua'if args.lifecycle else'room_hit_assertions.lua'if args.hit_test else'chest_assertions.lua')));print(status,flush=True)
 save(BUILD/('lifecycle-0.3.6/menu.json'if args.lifecycle else'menu-check/hit-regions.json'if args.hit_test else'game-softbody/menu-check.json'),dict(status=status,real_kahlua=True,game_slider_source=True,game_button_source=args.hit_test,actor_and_widgets_substituted=True,actual_gameplay_tested=False,game_started=False))
finally:jpype.shutdownJVM()
