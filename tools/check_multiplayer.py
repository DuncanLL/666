from pathlib import Path
import json, os, sys
root=Path(__file__).resolve().parents[1]
test=root/'tools/tests'
media=root/'game/mod/42/media/lua'
from common import settings
config=settings()
sys.path.insert(0,config['python_dependencies'])
import jpype
game=Path(config['game_root'])
os.chdir(game)
jpype.startJVM(str(game/'jre64/bin/server/jvm.dll'),'--enable-native-access=ALL-UNNAMED',classpath=[str(game/'projectzomboid.jar')],convertStrings=True)
try:
 J=jpype.JClass
 platform=J('se.krka.kahlua.j2se.J2SEPlatform').getInstance()
 env=platform.newEnvironment()
 thread=J('se.krka.kahlua.vm.KahluaThread')(platform,env)
 thread.debugOwnerThread=J('java.lang.Thread').currentThread()
 compiler=J('se.krka.kahlua.luaj.compiler.LuaCompiler')
 empty=jpype.JArray(J('java.lang.Object'))(0)
 def run(text,name):
  result=thread.pcall(compiler.loadstring(text,name,env),empty)
  if not result[0]:raise AssertionError([str(x) for x in result])
  return result[1] if len(result)>1 else None
 for p in media.rglob('*.lua'):
  compiler.loadstring(p.read_text('utf-8-sig'),p.name,env)
 print('PASS Lua compilation',len(list(media.rglob('*.lua'))))
 for name in ('AimohPMXRoomEconomy','GFL2MultiplayerProtocol','GFL2MultiplayerCatalog'):
  env.rawset(name,run((media/'shared'/f'{name}.lua').read_text('utf8'),name))
 run('function require(name) return _G[name] end','require')
 env.rawset('Economy',env.rawget('AimohPMXRoomEconomy'))
 print(run((root/'tools/tests/room_economy_assertions.lua').read_text('utf8'),'economy'))
 run((test/'multiplayer-server-fixture.lua').read_text('utf8'),'fixture')
 run((media/'server/AimohPMXNetwork.lua').read_text('utf8'),'server')
 print(run('return verifyServer()','assertions'))
 env.rawset('AimohPMXRoomEconomy',run((media/'shared/AimohPMXRoomEconomy.lua').read_text('utf8'),'economy-client'))
 run((test/'multiplayer-client-fixture.lua').read_text('utf8'),'client-fixture')
 env.rawset('client',run((media/'client/GFL2MultiplayerClient.lua').read_text('utf8'),'client'))
 print(run('return verifyClient(client,AimohPMXRoomEconomy)','client-assertions'))
finally:jpype.shutdownJVM()
