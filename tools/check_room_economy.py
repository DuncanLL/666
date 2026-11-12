"""Run the ready-room wallet and recruitment regression in the game's Kahlua runtime."""
from pathlib import Path
import os
import sys

from common import ROOT, settings

config = settings()
sys.path.insert(0, config["python_dependencies"])
import jpype

game = Path(config["game_root"])
os.chdir(game)
jpype.startJVM(
    str(game / "jre64/bin/server/jvm.dll"),
    "--enable-native-access=ALL-UNNAMED",
    classpath=[str(game / "projectzomboid.jar")],
    convertStrings=True,
)
try:
    J = jpype.JClass
    platform = J("se.krka.kahlua.j2se.J2SEPlatform").getInstance()
    environment = platform.newEnvironment()
    thread = J("se.krka.kahlua.vm.KahluaThread")(platform, environment)
    thread.debugOwnerThread = J("java.lang.Thread").currentThread()
    compiler = J("se.krka.kahlua.luaj.compiler.LuaCompiler")

    def run(path):
        closure = compiler.loadstring(path.read_text("utf-8-sig"), path.name, environment)
        result = thread.pcall(closure, jpype.JArray(J("java.lang.Object"))(0))
        if not result[0]:
            raise AssertionError([str(value) for value in result])
        return result[1]

    environment.rawset("Economy", run(ROOT / "game/mod/42/media/lua/client/AimohPMXRoomEconomy.lua"))
    print(run(ROOT / "tools/tests/room_economy_assertions.lua"))
finally:
    jpype.shutdownJVM()
