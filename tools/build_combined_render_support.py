"""Combine the two verified build inputs into one normally loaded R116 MOD JAR."""
import json
import os
from pathlib import Path
import subprocess
import zipfile
from build import compile_java
from build_render_support import write
from common import ROOT, BUILD, settings, environment, sha, save

BRIDGE='aimoh/gf2/bridge/'
RENDER='viewpoint/render/'
VARIANTS=(
    'DepthStencil','GlScope','NativeShaders','OriginalProgram','PackMaterials',
    'Rc2Vertex','RegisterBank','SourceHost','Vertex',
)
RENAMES={RENDER+'AimohGf2'+name:RENDER+'AimohEclipseGf2'+name for name in VARIANTS}
RENAMES[BRIDGE+'RuntimeHooks']=BRIDGE+'EclipseHooks'

def class_root(entry):
    return entry.removesuffix('.class').split('$',1)[0]

def inputs(folder):
    report=json.loads((BUILD/folder/'build.json').read_text('utf8'))
    assert sha(report['jar'])==report['jar_sha256'],'Input JAR changed'
    for name,digest in report['source_hashes'].items():
        assert sha(ROOT/name)==digest,'Input source changed: '+name
    return report

def build_combined():
    native=inputs('render-support');eclipse=inputs('eclipse-support')
    cfg=settings();host=Path(cfg['viewpoint_jar']);game=Path(cfg['game_root'])
    assert sha(host)==native['host_sha256']==eclipse['host_sha256'],'Combined inputs require the same verified host'
    out=BUILD/'combined-support';out.mkdir(exist_ok=True)
    relocator=ROOT/'tools/java/RelocateEclipse.java'
    compile_java(cfg,[relocator],out/'relocator',[cfg['zombiebuddy_jar']])
    mapping=out/'class-map.txt'
    mapping.write_text(''.join(a+'='+b+'\n' for a,b in sorted(RENAMES.items())),'utf8')
    excluded={BRIDGE+'Main',BRIDGE+'ManagedShaderPack'}
    with zipfile.ZipFile(native['jar'])as n,zipfile.ZipFile(eclipse['jar'])as e:
        selected=[]
        for name in e.namelist():
            if not name.endswith('.class') or class_root(name) in excluded:
                continue
            if class_root(name) in RENAMES or name not in n.namelist():
                selected.append(name)
            else:
                assert e.read(name)==n.read(name),'Unreviewed duplicate class: '+name
    with __import__('tempfile').TemporaryDirectory(dir=out) as temporary:
        staging=Path(temporary)
        classes=staging/'eclipse'
        cmd=[str(game/'jre64/bin/java.exe'),'-cp',str(out/'relocator')+';'+cfg['zombiebuddy_jar'],
             'RelocateEclipse',eclipse['jar'],str(mapping),str(classes),','.join(selected)]
        subprocess.run(cmd,env=environment(),check=True)
        sources=sorted((ROOT/'render-support/combined/java').rglob('*.java'))
        overrides=staging/'combined'
        compile_java(cfg,sources,overrides,[classes,native['jar'],host,cfg['zombiebuddy_jar'],cfg['reference_jar'],*game.glob('*.jar')])
        rows={}
        for base in (classes,overrides):
            for path in base.rglob('*.class'):
                name=path.relative_to(base).as_posix()
                assert name not in rows,'Combined entry conflict: '+name
                rows[name]=path.read_bytes()
        jar=out/'AimohGF2Eclipse.jar'
        result=staging/jar.name
        with zipfile.ZipFile(native['jar'])as n,zipfile.ZipFile(eclipse['jar'])as e,zipfile.ZipFile(result,'w')as z:
            replacements={BRIDGE+'Main.class',BRIDGE+'EclipseHooks.class',RENDER+'AimohGf2SourceHost.class'}
            assert set(rows)&set(n.namelist())==replacements,'Unexpected replacement of a Native class'
            for name in n.namelist():
                if name not in replacements:write(z,name,n.read(name))
            for name,data in sorted(rows.items()):write(z,name,data)
            write(z,eclipse['shader_payload'],e.read(eclipse['shader_payload']))
        with zipfile.ZipFile(host)as stock,zipfile.ZipFile(result)as combined:
            assert combined.testzip()is None
            assert not {n for n in combined.namelist() if n.endswith('.class')}&set(stock.namelist()),'Official host class collision'
        os.replace(result,jar)
    files=[*sources,relocator,Path(__file__).resolve(),ROOT/'tools/build_render_support.py',ROOT/'tools/build_eclipse_support.py']
    hashes={**native['source_hashes'],**eclipse['source_hashes'],**{p.relative_to(ROOT).as_posix():sha(p)for p in files}}
    backends={report['backend']:{key:report[key] for key in ('shader_payload','shader_sha256')}for report in (native,eclipse)}
    save(out/'build.json',dict(status='BUILT_COMBINED_RENDER_SUPPORT',backend='combined',version=native['version'],
        jar=str(jar),jar_sha256=sha(jar),host_sha256=sha(host),backends=backends,source_hashes=hashes,installed=False,uploaded=False))
    print('Built combined render support: '+str(jar),flush=True)

if __name__=='__main__':
    build_combined()
