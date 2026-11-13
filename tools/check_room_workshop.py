from pathlib import Path
import json,sys,os
from common import ROOT, settings
repo=ROOT
config=settings()
game=Path(config['game_root'])
sys.path.insert(0,config['python_dependencies'])
import jpype
catalog=json.loads((repo/'game/mod/42/media/ui/AimohPMXReadyRoom/catalog.json').read_text('utf8'))
reg=[]
for mod in (repo/'game/packs').iterdir():
 p=mod/'42/media/aimoh_pmx/packs'/f'{mod.name}.json'
 if p.exists():reg.extend({**r,'groups':[]} for r in json.loads(p.read_text('utf8'))['models'])
assert len(catalog['roomWorkshopItems'])==15
os.chdir(game)
jpype.startJVM(str(game/'jre64/bin/server/jvm.dll'),'--enable-native-access=ALL-UNNAMED',classpath=[str(game/'projectzomboid.jar')],convertStrings=True)
J=jpype.JClass
platform=J('se.krka.kahlua.j2se.J2SEPlatform').getInstance();env=platform.newEnvironment()
thread=J('se.krka.kahlua.vm.KahluaThread')(platform,env);thread.debugOwnerThread=J('java.lang.Thread').currentThread()
compiler=J('se.krka.kahlua.luaj.compiler.LuaCompiler')
def table(value):
 if isinstance(value,(dict,list)):
  t=platform.newTable()
  for k,v in (value.items()if isinstance(value,dict)else enumerate(value,1)):
   if v is not None:t.rawset(float(k)if isinstance(k,int)else k,table(v))
  return t
 if isinstance(value,bool):return J('java.lang.Boolean')(value)
 if isinstance(value,(int,float)):return J('java.lang.Double')(value)
 return value
def load(path):
 result=thread.pcall(compiler.loadstring(path.read_text('utf-8-sig'),path.name,env),jpype.JArray(J('java.lang.Object'))(0))
 if not result[0]:raise AssertionError([str(v)for v in result])
 return result[1]if len(result)>1 else None
try:
 for name in ('ui_fixture.lua','room_fixture.lua','room_hit_fixture.lua'):load(repo/'tools/tests'/name)
 env.rawset('roomData',table(catalog));env.rawset('registryModels',table([]));env.rawset('allModels',table(reg))
 env.rawset('economy',load(repo/'game/mod/42/media/lua/client/AimohPMXRoomEconomy.lua'))
 load(game/'media/lua/client/ISUI/ISButton.lua')
 load(repo/'tools/tests/room_workshop_fixture.lua')
 load(repo/'game/mod/42/media/lua/client/AimohPMXWindow.lua')
 result=load(repo/'tools/tests/room_workshop_assertions.lua');print(result)

finally:jpype.shutdownJVM()
