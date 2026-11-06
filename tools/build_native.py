"""Build existing native sources with private toolchains; never changes the active runtime pins."""
from pathlib import Path
import argparse
import shutil
import subprocess
from common import ROOT, BUILD, settings, save, sha, environment


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ozz', action='store_true', help='Build the unchanged Ozz bridge instead of the Rust/Bullet engine.')
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    config = settings()
    output = BUILD / 'native'
    output.mkdir(parents=True, exist_ok=True)
    env = environment()
    strip = None
    if args.ozz:
        source = ROOT / 'native/ozz'
        dll = output / 'AimohGripOzz.dll'
        env.update(ZIG_GLOBAL_CACHE_DIR=str(output / 'zig-global-cache'), ZIG_LOCAL_CACHE_DIR=str(output / 'zig-local-cache'))
        command = [config['zig'], 'c++', '-target', 'x86_64-windows-gnu', '-std=c++17', '-O2', '-g0',
                   '-fno-exceptions', '-fno-rtti', '-shared', '-I' + str(source / 'upstream/include'),
                   str(source / 'ozz_grip_bridge.cpp'), str(source / 'upstream/src/animation/runtime/ik_two_bone_job.cc'),
                   '-o', str(dll)]
    else:
        tools = Path(config['native_toolchain_root'])
        gcc = tools / 'winlibs/mingw64/bin'
        source = ROOT / 'native/mmd-engine'
        command = [str(tools / 'cargo/bin/cargo.exe'), 'build', '--release', '--locked', '--lib', '-j', '4']
        env.update(CARGO_HOME=str(tools / 'cargo'), RUSTUP_HOME=str(tools / 'rustup'),
                   RUSTUP_TOOLCHAIN='1.90.0-x86_64-pc-windows-gnu', CARGO_TARGET_DIR=str(output / 'target'),
                   CXX=str(gcc / 'g++.exe'), CC=str(gcc / 'gcc.exe'), AR=str(gcc / 'ar.exe'),
                   CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER=str(gcc / 'gcc.exe'), RUSTFLAGS='--cap-lints=warn')
        env['PATH'] = str(gcc) + ';' + str(tools / 'cargo/bin') + ';' + env['PATH']
        strip = gcc / 'strip.exe'
        dll = output / 'AimohMmdUpstream.dll'
    assert Path(command[0]).is_file(), command[0]
    if args.dry_run:
        print('cwd: ' + str(source)); print(subprocess.list2cmdline(command)); return
    with (output / ('ozz-build.log' if args.ozz else 'mmd-build.log')).open('w', encoding='utf8') as log:
        subprocess.run(command, cwd=source, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
    if strip:
        shutil.copy2(output / 'target/release/mmd_engine.dll', dll)
        subprocess.run([str(strip), '--strip-unneeded', str(dll)], check=True, env=env)
    save(output / ('ozz.json' if args.ozz else 'mmd.json'), {'dll': str(dll), 'sha256': sha(dll),
         'bytes': dll.stat().st_size, 'installed': False, 'active_runtime_pins_updated': False})
    print('Built native candidate only: ' + str(dll))


if __name__ == '__main__': main()
