"""Differentially prepare both author Workshop projects; preserve IDs and never upload."""
from pathlib import Path
import argparse,json,os,shutil,subprocess
from common import ROOT,BUILD,settings,sha,save,inventory

def properties(text):
    return {line.split('=',1)[0]:line.split('=',1)[1]for line in text.splitlines()if '='in line}

def promote(source,target,out,index,apply):
    source=source.resolve();target=target.resolve()
    assert target.parent.name=='Workshop' and target.name in {'R116','R116RenderSupport'}
    assert not source.is_relative_to(target)and not target.is_relative_to(source)
    wanted=inventory(source);old=inventory(target)if target.exists()else{}
    assert not old.keys()-wanted.keys(),'Unexpected files in author project; preserve and inspect first'
    info=(source/'workshop.txt').read_text('utf8');previous=properties((target/'workshop.txt').read_text('utf8'))if (target/'workshop.txt').exists()else{}
    selected=properties(info)
    if previous:
        # Keep the existing item ID, visibility, tags and uploader-managed metadata.
        lines=(target/'workshop.txt').read_text('utf8').splitlines()
        for key in ['title','description']:
            lines=[line for line in lines if not line.startswith(key+'=')];lines.append(key+'='+selected[key])
        info='\n'.join(lines)+'\n'
    assert properties(info).get('id')==previous.get('id')
    meta=out/'metadata'/f'{index}.txt';meta.parent.mkdir(parents=True,exist_ok=True);meta.write_text(info,'utf8')
    files={name:source/name for name in wanted};files['workshop.txt']=meta
    wanted['workshop.txt']={'bytes':meta.stat().st_size,'sha256':sha(meta)}
    changed=[name for name in sorted(wanted)if old.get(name)!=wanted[name]]
    result=dict(project=target.name,target=str(target),workshop_id=previous.get('id'),
                bytes=sum(row['bytes']for row in wanted.values()),files=len(wanted),
                changed_files=changed,changed_bytes=sum(wanted[n]['bytes']for n in changed),uploaded=False)
    if not apply:return dict(result,status='PLANNED')
    backups=out/'rollback';backups.mkdir(exist_ok=True);done=[]
    manifest=out/f'promotion-{index}.json';assert not manifest.exists(),'Do not replace a retained installation record'
    receipt=dict(result,status='PREPARED',before={name:old.get(name)for name in changed},after={name:wanted[name]for name in changed})
    save(manifest,receipt)
    try:
        for name in changed:
            final=(target/name).resolve();assert final.is_relative_to(target)
            before=old.get(name)
            assert (sha(final)if final.exists()else None)==(before['sha256']if before else None),'Target changed during preparation'
            if before:
                backup=backups/before['sha256']
                if not backup.exists():shutil.copy2(final,backup)
                assert sha(backup)==before['sha256']
            final.parent.mkdir(parents=True,exist_ok=True)
            temp=final.with_name(final.name+'.r116-release-writing');assert not temp.exists()
            try:shutil.copy2(files[name],temp);os.replace(temp,final)
            finally:temp.unlink(missing_ok=True)
            done.append(name);assert sha(final)==wanted[name]['sha256']
        for entry in (target/'Contents/mods').iterdir():
            if entry.is_dir():(entry/'common').mkdir(exist_ok=True)
        assert inventory(target)==wanted,'Workshop readback mismatch'
    except Exception:
        for name in reversed(done):
            final=target/name;before=old.get(name)
            if before:
                temp=final.with_name(final.name+'.r116-release-rollback');shutil.copy2(backups/before['sha256'],temp);os.replace(temp,final)
            else:final.unlink(missing_ok=True)
        raise
    save(out/f'manifest-{index}.json',wanted)
    result['status']='WORKSHOP_PROJECT_READY_READBACK_VERIFIED';receipt.update(result);save(manifest,receipt);return result

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--apply',action='store_true');args=parser.parse_args()
    cfg=settings();out=BUILD/'release-20261106';out.mkdir(exist_ok=True)
    character=BUILD/'workshop/chest-trial/R116';support=BUILD/'workshop/R116RenderSupport'
    body=json.loads((BUILD/'runtime-check.json').read_text('utf8'));render=json.loads((BUILD/'render-support/checks/validation.json').read_text('utf8'))
    assert body['jar_sha256']==sha(character/'Contents/mods/AimohPmxPreview/42/media/java/client/AimohPMXPreview.jar')
    assert render['jar_sha256']==sha(support/'Contents/mods/AimohGF2Eclipse/42/media/java/client/AimohGF2Eclipse.jar')
    assert not json.loads((BUILD/'render-support/checks/release-defaults.json').read_text('utf8'))['differences_from_live']
    reports=[]
    for root in map(Path,cfg['workshop_targets']):
        for source,target in [(character,root),(support,root.parent/'R116RenderSupport')]:
            row=promote(source,target,out,len(reports),args.apply);reports.append(row)
            print(json.dumps({k:v for k,v in row.items()if k!='changed_files'},ensure_ascii=False),flush=True)
    commit=subprocess.run(['git','rev-parse','HEAD'],capture_output=True,text=True,check=True).stdout.strip()
    save(out/('workshop-preparation.json'if args.apply else'workshop-plan.json'),dict(source_commit=commit,projects=reports,
         game_started=False,uploaded=False,strict_material_contract='Existing limits retained; see integration.json and docs/RELEASE-2026-11-06.md'))
