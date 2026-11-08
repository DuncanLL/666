"""Create the separate, no-description rendering-support Workshop project."""
from pathlib import Path
import argparse,json,shutil,zipfile
from common import ROOT,BUILD,settings,sha,save,inventory

def package(output,verify_existing=False,evidence=None,public=False):
    output=Path(output).resolve()
    report=json.loads((BUILD/'render-support/build.json').read_text('utf8'));jar=Path(report['jar'])
    assert sha(jar)==report['jar_sha256']
    for name,value in report['source_hashes'].items():assert sha(ROOT/name)==value,'Source changed after build: '+name
    if public:
        if evidence is None:raise ValueError('Public validation requires --evidence; ordinary output is a candidate only')
        from check_render_release import evaluate
        validation=evaluate(evidence)
        if validation['status']!='THREE_VENDOR_DRIVER_VALIDATED':raise ValueError(json.dumps(validation,ensure_ascii=False))
    if verify_existing:
        expected={n:r for n,r in inventory(ROOT/'render-support/mod').items()if n!='common/.gitkeep'}
        expected['42/media/java/client/AimohGF2Eclipse.jar']={'bytes':jar.stat().st_size,'sha256':sha(jar)}
        assert inventory(output/'Contents/mods/AimohGF2Eclipse')==expected
    else:
        assert not output.exists(),'Use --verify-existing to inspect a current candidate'
        stage=output.with_name(output.name+'.preparing');assert not stage.exists()
        mod=stage/'Contents/mods/AimohGF2Eclipse';shutil.copytree(ROOT/'render-support/mod',mod)
        (mod/'common/.gitkeep').unlink(missing_ok=True)
        target=mod/'42/media/java/client/AimohGF2Eclipse.jar';target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(jar,target)
        import sys
        sys.path.insert(0,settings()['python_dependencies'])
        from PIL import Image
        Image.new('RGB',(256,256),(32,32,32)).save(stage/'preview.png')
        (stage/'workshop.txt').write_text('version=1\ntitle=R116 渲染支持 光影分发\ndescription=\u200b\ntags=\nvisibility=unlisted\n','utf8')
        output.parent.mkdir(parents=True,exist_ok=True);stage.replace(output)
    target=output/'Contents/mods/AimohGF2Eclipse/42/media/java/client/AimohGF2Eclipse.jar'
    forbidden={'.dll','.exe','.zip','.bat','.app','.so','.sh','.dylib'}
    assert not [p for p in output.rglob('*')if p.is_file()and p.suffix.lower()in forbidden]
    with zipfile.ZipFile(target)as z:assert z.testzip()is None
    files=inventory(output)
    save(BUILD/'render-support/workshop.json',dict(status='WORKSHOP_CANDIDATE_NOT_UPLOADED',root=str(output),
         files=files,bytes=sum(r['bytes']for r in files.values()),jar_sha256=sha(target),
         title='R116 渲染支持 光影分发',visible_description=False,game_started=False,uploaded=False,
         validation_claim='THREE_VENDOR_DRIVER_VALIDATED'if public else 'CANDIDATE_ONLY_NOT_CROSS_VENDOR_ACCEPTED'))
    print('Workshop project: '+str(output),flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,default=BUILD/'workshop/R116RenderSupport')
    parser.add_argument('--verify-existing',action='store_true');parser.add_argument('--public',action='store_true');parser.add_argument('--evidence',type=Path);args=parser.parse_args()
    package(args.output,args.verify_existing,args.evidence,args.public)
