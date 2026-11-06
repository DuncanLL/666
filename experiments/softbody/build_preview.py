"""Build a separate preview DLL, using the existing pinned Bullet source/static library."""
from pathlib import Path
import hashlib,json,os,subprocess,sys

ROOT=Path(__file__).resolve().parents[2]
HERE=Path(__file__).resolve().parent
cfg=json.loads((ROOT/'.local/paths.json').read_text('utf8'))
out=ROOT/'build/softbody';out.mkdir(parents=True,exist_ok=True)
temp=out/'tmp';temp.mkdir(exist_ok=True)
tools=Path(cfg['native_toolchain_root']);gcc=tools/'winlibs/mingw64/bin'
engine=tools.parent
libraries=list((engine/'target-engine/release/build').glob('mmd_engine-*/out/libbullet3.a'))
assert len(libraries)==1,libraries
bullet=ROOT/'native/mmd-engine/deps/bullet3/src'
names=[p.relative_to(bullet/'BulletSoftBody').as_posix() for p in sorted((bullet/'BulletSoftBody').rglob('*.cpp'))]
sources=[bullet/'BulletSoftBody'/name for name in names]
dll=out/'AimohSoftBodyPreview.dll'
command=[str(gcc/'g++.exe'),'-std=c++17','-O2','-g0','-fno-exceptions','-fno-rtti',
         '-shared','-static-libgcc','-static-libstdc++','-I'+str(bullet),str(ROOT/'native/chest-softbody/softbody.cpp'),
         *map(str,sources),str(libraries[0]),'-o',str(dll)]
env=dict(os.environ,TEMP=str(temp),TMP=str(temp));env['PATH']=str(gcc)+';'+env['PATH']
with (out/'compile.log').open('w',encoding='utf8') as log:
    print('Compiling pinned Bullet soft-body sources with recorded fixed-node fixes and preview-only ABI',flush=True)
    r=subprocess.run(command,env=env,stdout=log,stderr=subprocess.STDOUT)
if r.returncode:
    print((out/'compile.log').read_text('utf8',errors='replace')[-6000:]);r.check_returncode()
report={'dll':str(dll),'bytes':dll.stat().st_size,'sha256':hashlib.sha256(dll.read_bytes()).hexdigest(),
        'bullet_commit':'63c4d67e337017f9d8b298c900e9aabdb69296e7','upstream_softbody_sources':names,
        'existing_rigid_static_library':str(libraries[0]),'production_native_unchanged':True,
        'local_upstream_fixes':['implicit applyForce increments index for fixed nodes','initialize optional gravity vector to zero'],
        'game_installed':False}
(out/'build.json').write_text(json.dumps(report,indent=2),'utf8');print(json.dumps(report),flush=True)
