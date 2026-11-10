"""Validate an actual Workshop project and its packaged native runtime without starting the game."""
from pathlib import Path
import json
import os
import sys
from common import ROOT, BUILD, settings, sha, save

config = settings()
project = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else BUILD / 'workshop/R116'
mod = project / 'Contents/mods/AimohPmxPreview'
temp = BUILD / 'tmp'
temp.mkdir(parents=True, exist_ok=True)
os.environ['TEMP'] = os.environ['TMP'] = str(temp)
sys.path.insert(0, config['python_dependencies'])
import jpype
game = Path(config['game_root'])
jar = mod / '42/media/java/client/AimohPMXPreview.jar'
# The game's file validator permits only its private user root (or game roots).
# Keep an out-of-workspace candidate inside this check's own permitted root.
home = BUILD if project.is_relative_to(BUILD) else project.parent.parent
home.mkdir(exist_ok=True)
jpype.startJVM(str(game / 'jre64/bin/server/jvm.dll'), '--enable-native-access=ALL-UNNAMED',
              '-Djava.io.tmpdir=' + str(temp), '-Duser.home=' + str(home),
              classpath=[str(jar), config['zombiebuddy_jar'], config['viewpoint_jar'], str(game), *map(str, game.glob('*.jar'))], convertStrings=True)
J = jpype.JClass
try:
    os.chdir(game)
    J('zombie.ZomboidFileSystem').instance.setCacheDir(str(home))
    J('zombie.ZomboidFileSystem').instance.init()
    if project.parent.name.casefold() == 'workshop':
        J('zombie.ZomboidFileSystem').instance.setCacheDir(str(project.parent.parent))
    item = J('zombie.core.znet.SteamWorkshopItem')(str(project))
    assert item.readWorkshopTxt()
    assert item.validateContents() is None
    assert item.getTitle() == 'R116' and item.getDescription() == '\u200b'
    J('zombie.ZomboidFileSystem').instance.setCacheDir(str(home))
    os.chdir(temp)
    main = J('aimoh.pmxpreview.Main')
    directory = main.class_.getDeclaredField('directory')
    directory.setAccessible(True); directory.set(None, J('java.nio.file.Path').of(str(mod / '42')))
    initialize = main.class_.getDeclaredMethod('nativeRuntime')
    initialize.setAccessible(True); initialize.invoke(None)
    api_field = main.class_.getDeclaredField('api'); api_field.setAccessible(True)
    api = api_field.get(None)
    build = json.loads((BUILD / 'build.json').read_text('utf8'))
    assert api.GetVersion() == build.get('native_version',json.loads((ROOT/'provenance/baseline.json').read_text('utf8'))['native_version'])
    assets = mod / '42/media/aimoh_pmx'
    registry = json.loads((assets / 'registry.json').read_text('utf8'))['models']
    checked = []
    for row in registry:
        profile = json.loads((assets / row['profile']).read_text('utf8'))
        assert sha(assets / row['model']) == profile['pmx_sha256']
        model = J('aimoh.motion.PmxModel').load(api, J('java.nio.file.Path').of(str(assets / row['model'])),
                                              J('java.nio.file.Path').of(str(assets / row['profile'])))
        try:
            pose = jpype.JArray(jpype.JFloat)(profile['source_reference'])
            for _ in range(4): model.apply(pose, None, 1/60)
            checked.append(row['id'])
        finally: model.close()
    stats = json.loads(str(api.GetRuntimeStats()))
    assert all(value == 0 for value in stats.values())
    assert J('aimoh.motion.ChestSoftBody').liveWorlds()==0
    field = main.class_.getDeclaredField('grip'); field.setAccessible(True); field.get(None).close()
    save(BUILD / 'runtime-check.json', {'status': 'PASS_WORKSHOP_VALIDATOR_AND_NATIVE_MODEL_COMPONENTS',
         'jar_sha256': sha(jar), 'models': checked, 'remaining_objects': stats,
         'game_started': False, 'workshop_uploaded': False, 'actual_gameplay_tested': False})
    print('PASS: Workshop validator, packaged native initialization, ' + str(len(checked)) + ' models, zero remaining objects.', flush=True)
except Exception as problem:
    if hasattr(problem, 'getMessage'):
        print('Java validation error: ' + str(problem.getMessage()), flush=True)
    raise
finally:
    jpype.shutdownJVM()
