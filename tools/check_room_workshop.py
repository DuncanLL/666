from pathlib import Path
import argparse,json,sys,os,re
from common import ROOT, settings
repo=ROOT
parser=argparse.ArgumentParser()
parser.add_argument('--locale',choices=['CN','EN','JP','KO'])
parser.add_argument('--resolution', type=int, nargs=2, default=(1920,1080))
parser.add_argument('--font-scale', type=int, choices=[1,2,3,4], default=1)
args=parser.parse_args()
locale=args.locale
config=settings()
game=Path(config['game_root'])
sys.path.insert(0,config['python_dependencies'])
import jpype
catalog=json.loads((repo/'game/mod/42/media/ui/GFL2ReadyRoom/catalog.json').read_text('utf8'))
registry=json.loads((repo/'game/mod/42/media/gfl2_pmx/registry.json').read_text('utf8'))
reg=[]
for pack, models in registry['packs'].items():
 p=repo/f'game/packs/{pack}/42/media/gfl2_pmx/packs/{pack}.json'
 if p.exists():
  reg.extend({**row,'groups':[]} for row in json.loads(p.read_text('utf8'))['models'] if row['id'] in models)
characters={row["character"] for row in catalog["models"].values()}
assert set(catalog["roomWorkshopItems"])<=characters
assert len(set(catalog["roomWorkshopItems"].values()))==len(catalog["roomWorkshopItems"])
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
def run(source,name):
 reader=J('java.io.StringReader')(source)
 result=thread.pcall(compiler.loadis(reader,name,env),jpype.JArray(J('java.lang.Object'))(0))
 if not result[0]:raise AssertionError([str(v)for v in result])
 return result[1]if len(result)>1 else None
def load(path):
 return run(path.read_text('utf-8-sig'),path.name)
try:
 for name in ('ui_fixture.lua','room_fixture.lua','room_hit_fixture.lua'):load(repo/'tools/tests'/name)
 env.rawset('roomData',table(catalog));env.rawset('registryModels',table([]));env.rawset('allModels',table(reg))
 env.rawset('signatureWeapons',load(repo/'game/mod/42/media/lua/shared/GFL2SignatureWeapon.lua'))
 env.rawset('weaponSkins',load(repo/'game/mod/42/media/lua/shared/GFL2WeaponSkins.lua'))
 env.rawset('economy',load(repo/'game/mod/42/media/lua/shared/GFL2RoomEconomy.lua'))
 load(game/'media/lua/client/ISUI/ISButton.lua')
 load(repo/'tools/tests/room_workshop_fixture.lua')
 env.rawset('screenWidth',float(args.resolution[0]))
 env.rawset('screenHeight',float(args.resolution[1]))
 if locale:
  translations=repo/'game/mod/42/media/lua/shared/Translate'
  def locale_text(lang):
   return {k:v for category in ('ContextMenu','UI')
           for k,v in json.loads((translations/lang/f'{category}.json').read_text('utf-8')).items()}
  locale_keys=locale_text(locale)
  env.rawset('roomTranslations',table(locale_text('EN')|locale_keys))
  biographies={
      row['character']:locale_keys['UI_GFL2_Biography_'+row['pack']]
      for row in catalog['models'].values() if 'UI_GFL2_Biography_'+row['pack'] in locale_keys}
  if biographies:
   env.rawset('localizedBiographies',table(biographies))
  outfit_descriptions={
      model:locale_keys['UI_GFL2_OutfitDescription_'+model]
      for model in catalog['models'] if 'UI_GFL2_OutfitDescription_'+model in locale_keys}
  if outfit_descriptions:
   env.rawset('localizedOutfitDescriptions',table(outfit_descriptions))
  suffix={'CN':'Chinese','EN':'','JP':'Japanese','KO':'Korean'}[locale]
  glyph_widths={}
  for size in ('Small','Medium'):
   font=(game/f'media/fonts/{locale}/{args.font_scale}x/zomboid{size}{suffix}.fnt').read_text('utf-8-sig')
   glyph_widths[size.lower()]={chr(int(number)):int(advance) for number,advance in re.findall(
       r'^char +id=(\d+) +.*?xadvance=(-?\d+)',font,re.M)}
   assert len(glyph_widths[size.lower()])>50
   line_height=int(re.search(r'^common\s+lineHeight=(\d+)',font,re.M)[1])
   env.rawset('stockFont'+size+'Height',float(line_height))
  def measure(frame, argument_count):
   glyph_width=glyph_widths[frame.get(1) if argument_count>=2 else 'small']
   frame.push(float(sum(glyph_width.get(char, glyph_width['?']) for char in frame.get(0))))
   return 1
  env.rawset('measureStockFont',jpype.JProxy('se.krka.kahlua.vm.JavaFunction',dict(call=measure)))
  env.rawset('stockFontHeight',env.rawget('stockFontSmallHeight'))
  env.rawset('gameLanguage',locale)
  env.rawset('testExpression','左眼眨眼（2）')
  env.rawset('testPart',table({'group':'头饰','options':['原样','无附加头饰']}))
  run("""local original=getText
Translator={getLanguage=function()return {name=function()return gameLanguage end}end}
function getTextOrNull(key) return roomTranslations[key] end
function getText(key,...) return getTextOrNull(key) or original(key,...) end""",'locale-fixture')
 env.rawset('multiplayerProtocol',load(repo/'game/mod/42/media/lua/shared/GFL2MultiplayerProtocol.lua'))
 run('client=true','multiplayer-mode')
 env.rawset('multiplayerRoom',load(repo/'game/mod/42/media/lua/client/GFL2MultiplayerClient.lua'))
 run('client=false','single-player-mode')
 load(repo/'game/mod/42/media/lua/client/GFL2RoomWindow.lua')
 result=load(repo/'tools/tests/room_workshop_assertions.lua');print(result)

finally:jpype.shutdownJVM()
