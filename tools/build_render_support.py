"""Build the Native-only support MOD and deterministic JAR-owned shader payload."""
from pathlib import Path
import argparse,io,json,os,re,zipfile
from common import ROOT,BUILD,settings,sha,save
from build import compile_java

def write(z,name,data):
    entry=zipfile.ZipInfo(name,date_time=(1980,1,1,0,0,0));entry.compress_type=zipfile.ZIP_DEFLATED;entry.create_system=3;entry.external_attr=0o100644<<16;z.writestr(entry,data)

_RESERVED='packed common partition active asm class union enum typedef template this resource goto inline noinline public static extern external interface long short half fixed unsigned superp input output hvec2 hvec3 hvec4 fvec2 fvec3 fvec4 filter sizeof cast namespace using sampler3DRect'.split()
_DECL=re.compile(r'\b(?:float|double|bool|int|uint|[biu]?vec[234]|mat[234](?:x[234])?)\s+('+'|'.join(_RESERVED)+r')\b')
_SHADER_SUFFIXES={'.glsl','.comp','.frag','.vert','.geom','.tesc','.tese','.csh','.fsh','.vsh','.gsh','.tcs','.tes'}

def check_glsl_names(name,data):
    if Path(name).suffix.lower() not in _SHADER_SUFFIXES:return
    code=re.sub(r'/\*.*?\*/|//[^\n]*','',data.decode('utf8',errors='replace'),flags=re.S)
    invalid=_DECL.search(code)
    assert invalid is None,'Non-portable GLSL identifier '+invalid.group(1)+' in '+str(name)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--read-assets-from',type=Path,help='Recover the verified shared display asset from an existing matched support JAR before building');args=parser.parse_args()
    cfg=settings();origin=json.loads((ROOT/'provenance/render-support.json').read_text('utf8'));host=Path(cfg['viewpoint_jar']);assert sha(host)==origin['official_host_sha256'],'Unsupported official VP build'
    resources=json.loads((ROOT/'resources/manifest.json').read_text('utf8'))['render_support']['original_display_profile']
    asset_root=Path(cfg['render_support_asset_root']).resolve();asset=(asset_root/resources['file']).resolve();assert asset.is_relative_to(asset_root)
    if args.read_assets_from:
        with zipfile.ZipFile(args.read_assets_from)as z:raw=z.read('aimoh/gf2/bridge/gf2-original-display.rgba16f')
        import hashlib
        assert len(raw)==resources['bytes']and hashlib.sha256(raw).hexdigest()==resources['sha256'],'Readback display asset differs'
        asset_root.mkdir(parents=True,exist_ok=True)
        if asset.exists():assert sha(asset)==resources['sha256'],'Existing source asset differs; preserved'
        else:asset.write_bytes(raw)
    assert asset.stat().st_size==resources['bytes']and sha(asset)==resources['sha256'],'Original shared display profile differs'
    game=Path(cfg['game_root']);out=BUILD/'render-support';out.mkdir(parents=True,exist_ok=True);classes=out/('classes-'+origin['release_version']);sources=sorted((ROOT/'render-support/java').rglob('*.java'))
    compile_java(cfg,sources,classes,[host,cfg['zombiebuddy_jar'],cfg['reference_jar'],*game.glob('*.jar')])
    packroot=ROOT/'render-support/native-pack';payload=io.BytesIO();shader_sources=sorted(p for p in packroot.rglob('*')if p.is_file())
    # Cross-driver guard: include legacy names accepted by some modern compilers.
    for source in shader_sources:check_glsl_names(source,source.read_bytes())
    profile=json.loads((packroot/'shaders/gf2/manifest.json').read_text('utf8'))['original_display_profile'];assert profile['sha256']==resources['sha256']and profile['width']*profile['height']*8==resources['bytes']
    with zipfile.ZipFile(payload,'w',zipfile.ZIP_DEFLATED)as z:
        for p in shader_sources:write(z,p.relative_to(packroot).as_posix(),p.read_bytes())
    shader=payload.getvalue();jar=out/'AimohGF2Eclipse.jar';temporary=jar.with_suffix('.jar.writing')
    with zipfile.ZipFile(host)as z:stock=set(z.namelist())
    with zipfile.ZipFile(temporary,'w',zipfile.ZIP_DEFLATED)as z:
        for p in sorted(classes.rglob('*.class')):
            name=p.relative_to(classes).as_posix();assert name not in stock,'Stock VP class collision: '+name;write(z,name,p.read_bytes())
        write(z,'aimoh/gf2/bridge/Aimoh-Native-GF2.zip',shader)
        write(z,'aimoh/gf2/bridge/gf2-original-display.rgba16f',asset.read_bytes())
    with zipfile.ZipFile(temporary)as z:
        assert z.testzip()is None;assert not any('Aimoh-Eclipse-GF2.zip'in n for n in z.namelist())
    os.replace(temporary,jar)
    import hashlib
    save(out/'build.json',dict(status='BUILT_NATIVE_ONLY_EMBEDDED_SUPPORT',jar=str(jar),jar_sha256=sha(jar),jar_bytes=jar.stat().st_size,shader_sha256=hashlib.sha256(shader).hexdigest(),shader_payload='aimoh/gf2/bridge/Aimoh-Native-GF2.zip',host_sha256=sha(host),backend='native',version=origin['release_version'],source_hashes={p.relative_to(ROOT).as_posix():sha(p)for p in [*sources,*shader_sources,ROOT/'resources/manifest.json',ROOT/'provenance/render-support.json']},original_display_asset_sha256=resources['sha256'],original_host_classes_packaged=0,eclipse_payloads=0,game_started=False,uploaded=False))
    print('Built Native-only support: '+str(jar),flush=True)
