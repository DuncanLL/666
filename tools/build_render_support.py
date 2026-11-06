"""Build the frozen Eclipse integration and embed its existing shader for Workshop."""
from pathlib import Path
import hashlib,json,os,zipfile
from common import ROOT,BUILD,settings,sha,save
from build import compile_java

if __name__=='__main__':
    cfg=settings();origin=json.loads((ROOT/'provenance/render-support.json').read_text('utf8'))
    shader=Path(cfg['render_support_shader_pack']);reference=Path(cfg['render_support_reference_jar'])
    assert sha(shader)==origin['shader_pack']['sha256'] and shader.stat().st_size==origin['shader_pack']['bytes']
    assert sha(reference)==origin['reference_jar_sha256']
    host=Path(cfg['viewpoint_jar']);assert sha(host)==origin['official_host_sha256']
    with zipfile.ZipFile(shader)as z:assert z.testzip()is None
    from prepare_shader_defaults import prepare
    shader=prepare(shader)
    sources=sorted((ROOT/'render-support/java').rglob('*.java'))
    for source in sources:
        relative=source.relative_to(ROOT).as_posix()
        if relative!='render-support/java/aimoh/gf2/bridge/Main.java':
            actual=hashlib.sha256(source.read_bytes().replace(b'\r\n',b'\n')).hexdigest()
            assert actual==origin['imported_sources'][relative]['canonical_sha256'],'Unreviewed renderer change: '+relative
    game=Path(cfg['game_root']);out=BUILD/'render-support';classes=out/'classes'
    compile_java(cfg,sources,classes,[host,cfg['zombiebuddy_jar'],BUILD/'AimohPMXPreview.jar',*game.glob('*.jar')])
    jar=out/'AimohGF2Eclipse.jar';temporary=jar.with_suffix('.jar.writing')
    with zipfile.ZipFile(host)as z:stock=set(z.namelist())
    with zipfile.ZipFile(temporary,'w',zipfile.ZIP_DEFLATED)as z:
        def write(name,data):
            entry=zipfile.ZipInfo(name,date_time=(1980,1,1,0,0,0));entry.compress_type=zipfile.ZIP_DEFLATED
            entry.create_system=3;entry.external_attr=0o100644<<16;z.writestr(entry,data)
        for source in sorted(classes.rglob('*.class')):
            name=source.relative_to(classes).as_posix();assert name not in stock,'Stock VP class collision: '+name
            write(name,source.read_bytes())
        write('aimoh/gf2/bridge/Aimoh-Eclipse-GF2.zip',shader.read_bytes())
    with zipfile.ZipFile(temporary)as z:assert z.testzip()is None
    os.replace(temporary,jar)
    save(out/'build.json',dict(status='BUILT_RENDERER_AND_EMBEDDED_ECLIPSE',jar=str(jar),jar_sha256=sha(jar),
         jar_bytes=jar.stat().st_size,shader_sha256=sha(shader),host_sha256=sha(host),
         source_hashes={p.relative_to(ROOT).as_posix():sha(p)for p in [*sources,ROOT/'render-support/pack-defaults.json']},
         original_host_classes_packaged=0,game_started=False,uploaded=False))
    print('Built '+str(jar),flush=True)
