"""Build the Native-only support MOD and deterministic JAR-owned shader payload."""
from pathlib import Path
import io,json,os,zipfile
from common import ROOT,BUILD,settings,sha,save
from build import compile_java

def write(z,name,data):
    entry=zipfile.ZipInfo(name,date_time=(1980,1,1,0,0,0));entry.compress_type=zipfile.ZIP_DEFLATED;entry.create_system=3;entry.external_attr=0o100644<<16;z.writestr(entry,data)

if __name__=='__main__':
    cfg=settings();origin=json.loads((ROOT/'provenance/render-support.json').read_text('utf8'));host=Path(cfg['viewpoint_jar']);assert sha(host)==origin['official_host_sha256'],'Unsupported official VP build'
    game=Path(cfg['game_root']);out=BUILD/'render-support';out.mkdir(parents=True,exist_ok=True);classes=out/('classes-'+origin['release_version']);sources=sorted((ROOT/'render-support/java').rglob('*.java'))
    compile_java(cfg,sources,classes,[host,cfg['zombiebuddy_jar'],cfg['reference_jar'],*game.glob('*.jar')])
    packroot=ROOT/'render-support/native-pack';payload=io.BytesIO();shader_sources=sorted(p for p in packroot.rglob('*')if p.is_file())
    with zipfile.ZipFile(payload,'w',zipfile.ZIP_DEFLATED)as z:
        for p in shader_sources:write(z,p.relative_to(packroot).as_posix(),p.read_bytes())
    shader=payload.getvalue();jar=out/'AimohGF2Eclipse.jar';temporary=jar.with_suffix('.jar.writing')
    with zipfile.ZipFile(host)as z:stock=set(z.namelist())
    with zipfile.ZipFile(temporary,'w',zipfile.ZIP_DEFLATED)as z:
        for p in sorted(classes.rglob('*.class')):
            name=p.relative_to(classes).as_posix();assert name not in stock,'Stock VP class collision: '+name;write(z,name,p.read_bytes())
        write(z,'aimoh/gf2/bridge/Aimoh-Native-GF2.zip',shader)
    with zipfile.ZipFile(temporary)as z:
        assert z.testzip()is None;assert not any('Aimoh-Eclipse-GF2.zip'in n for n in z.namelist())
    os.replace(temporary,jar)
    import hashlib
    save(out/'build.json',dict(status='BUILT_NATIVE_ONLY_EMBEDDED_SUPPORT',jar=str(jar),jar_sha256=sha(jar),jar_bytes=jar.stat().st_size,shader_sha256=hashlib.sha256(shader).hexdigest(),shader_payload='aimoh/gf2/bridge/Aimoh-Native-GF2.zip',host_sha256=sha(host),backend='native',version=origin['release_version'],source_hashes={p.relative_to(ROOT).as_posix():sha(p)for p in [*sources,*shader_sources]},original_host_classes_packaged=0,eclipse_payloads=0,game_started=False,uploaded=False))
    print('Built Native-only support: '+str(jar),flush=True)
