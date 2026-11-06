"""Install only the reviewed runtime/menu delta; preserve models, shaders and Workshop IDs."""
from pathlib import Path
import argparse,json,os,shutil
from common import ROOT,BUILD,settings,sha,save

def install(project,target,index):
    source=project/'Contents/mods/AimohPmxPreview';target=target.resolve()
    assert (target/'42/mod.info').is_file() and (target/'42/media/aimoh_pmx/registry.json').is_file(),target
    generated=BUILD/'generated-mod';catalog_path=Path('42/media/ui/AimohPMXReadyRoom/catalog.json')
    from prepare_chest_labels import TEXT
    next_catalog=json.loads((target/catalog_path).read_text('utf8'));candidate=json.loads((source/catalog_path).read_text('utf8'))
    for key,value in TEXT.items():next_catalog['text'][key]=value;next_catalog['labels'][value]=candidate['labels'][value]
    files={Path('42/media/java/client/AimohPMXPreview.jar'):source/'42/media/java/client/AimohPMXPreview.jar',
           Path('42/mod.info'):source/'42/mod.info'}
    for name in ['AimohPMXPreview.lua','AimohPMXWindow.lua']:
        relative=Path('42/media/lua/client')/name;files[relative]=source/relative
    for p in generated.rglob('*.png'):files[p.relative_to(generated)]=p
    stage=BUILD/'game-softbody/install-delta'/str(index);stage.mkdir(parents=True,exist_ok=True)
    catalog_file=stage/'catalog.json';catalog_file.write_text(json.dumps(next_catalog,ensure_ascii=False,indent=2)+'\n','utf8');files[catalog_path]=catalog_file
    registry_path=Path('42/media/aimoh_pmx/registry.json')
    registry=json.loads((target/registry_path).read_text('utf8'));listed={r['id']:r for r in json.loads((source/registry_path).read_text('utf8'))['models']}
    for row in registry['models']:
        if row['id'] in listed:
            if listed[row['id']].get('hidden',False):row['hidden']=True
            else:row.pop('hidden',None)
    registry_file=stage/'registry.json';registry_file.write_text(json.dumps(registry,ensure_ascii=False,indent=2)+'\n','utf8');files[registry_path]=registry_file
    backup=BUILD/'game-softbody/rollback'/str(index);backup.mkdir(parents=True,exist_ok=True)
    assert not (backup/'manifest.json').exists(),'Do not replace the retained rollback'
    records=[];replaced=[]
    for relative,path in files.items():
        final=(target/relative).resolve();assert final.is_relative_to(target)
        old=sha(final)if final.exists()else None
        if old is not None:
            dest=backup/relative;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(final,dest)
        records.append(dict(path=relative.as_posix(),before=old,after=sha(path)))
    save(backup/'manifest.json',dict(target=str(target),files=records))
    try:
        for row in records:
            relative=Path(row['path']);final=target/relative;final.parent.mkdir(parents=True,exist_ok=True)
            assert (sha(final)if final.exists()else None)==row['before'],'Target changed during installation: '+str(final)
            temporary=final.with_name(final.name+'.r116-trial-writing');assert not temporary.exists()
            try:shutil.copy2(files[relative],temporary);os.replace(temporary,final)
            finally:temporary.unlink(missing_ok=True)
            replaced.append(row);assert sha(final)==row['after']
    except Exception:
        for row in reversed(replaced):
            final=target/row['path']
            if row['before'] is None:final.unlink()
            else:
                temporary=final.with_name(final.name+'.r116-rollback-writing');shutil.copy2(backup/row['path'],temporary);os.replace(temporary,final)
        raise
    return dict(target=str(target),status='INSTALLED_READBACK_VERIFIED_GAMEPLAY_NOT_TESTED',files=records,rollback=str(backup),models_and_textures_replaced=False,external_shaders_replaced=False)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--project',type=Path,default=BUILD/'workshop/chest-trial/R116');args=p.parse_args()
    c=settings();roots=[Path(p)for p in c['game_install_roots']];reports=[]
    for index,root in enumerate(roots):
        try:reports.append(install(args.project.resolve(),root,index))
        except Exception as e:reports.append(dict(target=str(root),status='NOT_INSTALLED_OR_ROLLED_BACK',error=str(e)))
    save(BUILD/'game-softbody/install.json',dict(version='0.3.0',targets=reports,game_started=False,gameplay_tested=False,uploaded=False))
    for row in reports:print(row['target'],row['status'],row.get('error',''),flush=True)
    assert all(r['status'].startswith('INSTALLED_')for r in reports),'One or more targets remain pending'
