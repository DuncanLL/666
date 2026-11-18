"""Combine Native and Somber, with Eclipse as an explicit maintenance build option."""
import argparse
from tempfile import TemporaryDirectory
import hashlib
import json
import build_somber_pack
import os
from pathlib import Path
import subprocess
import zipfile
from build import compile_java
from build_render_support import write
from common import ROOT, BUILD, settings, environment, sha, save

BRIDGE='gfl2/render/'
RENDER='viewpoint/render/'
VARIANTS=(
    'DepthStencil','GlScope','NativeShaders','OriginalProgram','PackMaterials',
    'Rc2Vertex','RegisterBank','SourceHost','Vertex',
)
RENAMES={RENDER+'Gfl2'+name:RENDER+'Gfl2Eclipse'+name for name in VARIANTS}
RENAMES[BRIDGE+'RuntimeHooks']=BRIDGE+'EclipseHooks'

def class_root(entry):
    return entry.removesuffix('.class').split('$',1)[0]

def inputs(folder):
    report=json.loads((BUILD/folder/'build.json').read_text('utf8'))
    assert sha(report['jar'])==report['jar_sha256'],'Input JAR changed'
    for name,digest in report['source_hashes'].items():
        assert sha(ROOT/name)==digest,'Input source changed: '+name
    return report

def relocate_eclipse(config, native, eclipse, staging):
    relocator=ROOT/'tools/java/RelocateEclipse.java'
    compiled=staging/'relocator'
    compile_java(config,[relocator],compiled,[config['zombiebuddy_jar']])
    mapping=staging/'class-map.txt'
    mapping.write_text(''.join(a+'='+b+'\n' for a,b in sorted(RENAMES.items())),'utf8')
    excluded={BRIDGE+'Main',BRIDGE+'ManagedShaderPack'}
    with zipfile.ZipFile(native['jar']) as n, zipfile.ZipFile(eclipse['jar']) as e:
        selected=[]
        for name in e.namelist():
            if not name.endswith('.class') or class_root(name) in excluded:
                continue
            if class_root(name) in RENAMES or name not in n.namelist():
                selected.append(name)
            else:
                assert e.read(name)==n.read(name),'Unreviewed duplicate class: '+name
    classes=staging/'eclipse'
    java=Path(config['game_root'])/'jre64/bin/java.exe'
    subprocess.run([str(java),'-cp',str(compiled)+';'+config['zombiebuddy_jar'],
        'RelocateEclipse',eclipse['jar'],str(mapping),str(classes),','.join(selected)],env=environment(),check=True)
    return classes,relocator


def build_combined(with_eclipse=False):
    native=inputs('render-support')
    eclipse=inputs('eclipse-support') if with_eclipse else None
    cfg=settings();host=Path(cfg['viewpoint_jar']);game=Path(cfg['game_root'])
    reports=[native,eclipse] if eclipse else [native]
    assert all(sha(host)==report['host_sha256'] for report in reports),'Combined inputs require the same verified host'
    out=BUILD/'combined-support';out.mkdir(exist_ok=True)
    folder=ROOT/'render-support/combined/java'
    sources=sorted((folder if eclipse else folder/'gfl2').rglob('*.java'))
    files=[*sources,Path(__file__).resolve(),ROOT/'tools/build_render_support.py']
    replacements={BRIDGE+'Main.class'}
    with TemporaryDirectory(dir=out) as temporary:
        staging=Path(temporary)
        classpaths=[]
        if eclipse:
            classes,relocator=relocate_eclipse(cfg,native,eclipse,staging)
            classpaths.append(classes)
            files.extend([relocator,ROOT/'tools/build_eclipse_support.py'])
            replacements.update({BRIDGE+'EclipseHooks.class',RENDER+'Gfl2SourceHost.class'})
        overrides=staging/'combined'
        compile_java(cfg,sources,overrides,[*classpaths,native['jar'],host,cfg['zombiebuddy_jar'],BUILD/'GFL2Exilium.jar',*game.glob('*.jar')])
        rows={}
        for base in [*classpaths,overrides]:
            for path in base.rglob('*.class'):
                name=path.relative_to(base).as_posix()
                assert name not in rows,'Combined entry conflict: '+name
                rows[name]=path.read_bytes()
        jar=out/'GFL2RenderSupport.jar'
        result=staging/jar.name
        with zipfile.ZipFile(native['jar']) as n, zipfile.ZipFile(result,'w') as z:
            assert set(rows)&set(n.namelist())==replacements,'Unexpected replacement of a Native class'
            for name in n.namelist():
                if name not in replacements:write(z,name,n.read(name))
            for name,data in sorted(rows.items()):write(z,name,data)
            if eclipse:
                with zipfile.ZipFile(eclipse['jar']) as e:
                    write(z,eclipse['shader_payload'],e.read(eclipse['shader_payload']))
            somber=build_somber_pack.build_somber(n.read(native['shader_payload']),host)
            write(z,build_somber_pack.PAYLOAD,somber)
        with zipfile.ZipFile(host) as stock, zipfile.ZipFile(result) as combined:
            assert combined.testzip() is None
            assert not {n for n in combined.namelist() if n.endswith('.class')}&set(stock.namelist()),'Official host class collision'
        os.replace(result,jar)
    files.extend([Path(build_somber_pack.__file__), *[p for p in build_somber_pack.OVERLAY.rglob("*") if p.is_file()]])
    hashes={name:digest for report in reports for name,digest in report['source_hashes'].items()}
    hashes.update({p.relative_to(ROOT).as_posix():sha(p)for p in files})
    backends={report['backend']:{key:report[key] for key in ('shader_payload','shader_sha256')}for report in reports}
    backends['somber']=dict(shader_payload=build_somber_pack.PAYLOAD,shader_sha256=hashlib.sha256(somber).hexdigest())
    save(out/'build.json',dict(status='BUILT_COMBINED_RENDER_SUPPORT',backend='combined',version=native['version'],
        jar=str(jar),jar_sha256=sha(jar),host_sha256=sha(host),backends=backends,source_hashes=hashes,installed=False,uploaded=False))
    print('Built combined render support: '+str(jar),flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--with-eclipse',action='store_true',help='Include the separately built Eclipse backend for maintenance')
    build_combined(parser.parse_args().with_eclipse)
