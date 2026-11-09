"""Build the Eclipse distribution on the verified official Viewpoint host."""
from pathlib import Path
import argparse,os,shutil,zipfile
from common import ROOT,BUILD,settings,sha,save
from build import compile_java

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--workshop',type=Path);parser.add_argument('--metadata-from',type=Path);args=parser.parse_args()
    cfg=settings();import json
    origin=json.loads((ROOT/'provenance/eclipse-outline.json').read_text('utf8'))
    reference=Path(cfg['eclipse_support_reference_jar']);host=Path(cfg['viewpoint_jar']);game=Path(cfg['game_root'])
    assert sha(reference)==origin['reference_jar_sha256'],'Preserved Eclipse baseline differs'
    assert sha(host)==origin['official_host_sha256'],'Unsupported official VP build'
    out=BUILD/'eclipse-support';out.mkdir(exist_ok=True)
    shared=[ROOT/'render-support/java'/name for name in origin['shared_sources']]
    sources=[*sorted((ROOT/'render-support/eclipse/java').rglob('*.java')),*shared]
    classes=out/'classes-outline';compile_java(cfg,sources,classes,[reference,host,cfg['zombiebuddy_jar'],cfg['reference_jar'],*game.glob('*.jar')])
    replacements={p.relative_to(classes).as_posix():p.read_bytes()for p in classes.rglob('*.class')}
    jar=out/'AimohGF2Eclipse.jar';temporary=jar.with_suffix('.jar.writing')
    with zipfile.ZipFile(host)as z:stock=set(z.namelist())
    assert not stock.intersection(replacements),'Official class collision'
    with zipfile.ZipFile(reference)as old,zipfile.ZipFile(temporary,'w',zipfile.ZIP_DEFLATED)as new:
        for name in old.namelist():
            if name not in replacements:new.writestr(old.getinfo(name),old.read(name))
        for name,data in sorted(replacements.items()):
            info=zipfile.ZipInfo(name,date_time=(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;new.writestr(info,data)
    with zipfile.ZipFile(reference)as old,zipfile.ZipFile(temporary)as new:
        assert new.testzip()is None
        for name in old.namelist():
            if name not in replacements:assert old.read(name)==new.read(name),'Unrelated Eclipse payload changed: '+name
    os.replace(temporary,jar)
    with zipfile.ZipFile(jar)as archive:
        import hashlib
        payload='aimoh/gf2/bridge/Aimoh-Eclipse-GF2.zip'
        shader_sha=hashlib.sha256(archive.read(payload)).hexdigest()
    save(out/'build.json',dict(status='ECLIPSE_CANDIDATE_NOT_INSTALLED',backend='eclipse',version=origin['version'],jar=str(jar),jar_sha256=sha(jar),base_jar_sha256=sha(reference),host_sha256=sha(host),replaced_entries=sorted(replacements),source_hashes={p.relative_to(ROOT).as_posix():sha(p)for p in [*sources,ROOT/'provenance/eclipse-outline.json']},shader_payload=payload,shader_sha256=shader_sha,shader_payload_unchanged=True,official_host_unchanged=True,game_started=False,installed=False,uploaded=False))
    if args.workshop:
        assert args.metadata_from is not None,'Preserve the existing Workshop metadata with --metadata-from'
        target=args.workshop.resolve();assert not target.exists(),'Preserve existing candidates; choose a new output'
        mod=target/'Contents/mods/AimohGF2Eclipse';shutil.copytree(ROOT/'render-support/eclipse/mod',mod)
        (mod/'common/.gitkeep').unlink(missing_ok=True);dest=mod/'42/media/java/client/AimohGF2Eclipse.jar';dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(jar,dest)
        for name in ('workshop.txt','preview.png'):shutil.copy2(args.metadata_from/name,target/name)
        assert sha(dest)==sha(jar)
        save(out/'workshop.json',dict(status='ECLIPSE_CANDIDATE_ONLY',path=str(target),jar_sha256=sha(dest),installed=False,uploaded=False))
    print('Built preserved Eclipse candidate: '+str(jar),flush=True)
