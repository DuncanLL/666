"""Check real Java-to-Lua catalog ownership in a private JVM; no game or asset writes."""
from pathlib import Path
import json,sys
from common import ROOT,BUILD,settings,save,sha
c=settings();sys.path.insert(0,c['python_dependencies'])
import jpype
game=Path(c['game_root']);jar=BUILD/'AimohPMXPreview.jar'
jpype.startJVM(str(game/'jre64/bin/server/jvm.dll'),'--enable-native-access=ALL-UNNAMED',classpath=[str(jar),c['viewpoint_jar'],c['zombiebuddy_jar'],*map(str,game.glob('*.jar'))],convertStrings=True)
try:
    J=jpype.JClass;fs=J('zombie.ZomboidFileSystem').instance;mapping=fs.getClass().getDeclaredField('modIdToDir');mapping.setAccessible(True)
    mapping.get(fs).put('GFL2Exilium',c['asset_root'])
    room=J('aimoh.pmxpreview.ReadyRoomPreview');first=room.data();first.rawget('models').wipe();first.rawget('text').rawset('Menu','altered-by-old-window')
    second=room.data();models=second.rawget('models');assert models.rawget('pmx-293917f24c2bd387')is not None
    assert second.rawget('text').rawget('Menu')=='整备室'
    save(BUILD/'room-loading-0.3.12/catalog-check.json',dict(status='PASS_FRESH_REAL_KAHLUA_CATALOG_AFTER_OLD_TABLE_MUTATION',jar_sha256=sha(jar),assets_modified=False,game_started=False,actual_gameplay_tested=False))
    print('PASS: new window receives complete catalog independent of old Lua tables')
finally:jpype.shutdownJVM()
