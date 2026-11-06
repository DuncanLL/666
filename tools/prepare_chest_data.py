"""Derive the proven soft-body inputs from original PMX; never rewrite assets."""
from pathlib import Path
import hashlib,json,struct,sys
from common import ROOT,BUILD,settings,save

def prepare(config=None):
    c=config or settings()
    sys.path[:0]=[c['python_dependencies'],c['pmx_reader_scripts']]
    from chest_model_data import inspect,regions
    base=Path(c['asset_root'])/'42/media/aimoh_pmx'
    registry=json.loads((ROOT/'game/mod/42/media/aimoh_pmx/registry.json').read_text('utf8'))['models']
    output=BUILD/'chest-data';output.mkdir(parents=True,exist_ok=True)
    data={};report=[]
    for row in registry:
        source=base/row['model'];profile=json.loads((ROOT/'game/mod/42/media/aimoh_pmx'/row['profile']).read_text('utf8'))
        sha=hashlib.sha256(source.read_bytes()).hexdigest()
        assert sha==profile['pmx_sha256'],row['id']
        model,info=inspect(source)
        if not info['usable']:
            report.append(dict(id=row['id'],label=row['label'],supported=False));continue
        groups=regions(model,info['bones'],1./profile['unit_scale'])
        payload=bytearray(b'ACSB0001'+bytes.fromhex(sha)+struct.pack('<III',profile['bones'],profile['vertices'],len(groups)))
        for r in groups:
            count=len(r['indices'])
            payload+=struct.pack('<IIf9f',r['bone'],count,r['mass'],*r['center'],*r['radius'],*r['attachment'])
            for key,dtype in [('indices','<u4'),('weights','<f4'),('positions','<f4'),('normals','<f4')]:
                payload+=r[key].astype(dtype).tobytes()
        file=output/(sha+'.bin');file.write_bytes(payload)
        data['aimoh/pmxpreview/chest/'+file.name]=file
        report.append(dict(id=row['id'],label=row['label'],supported=True,sha256=sha,bones=info['bones'],
            region_vertices=[len(r['indices'])for r in groups],bytes=len(payload),data_sha256=hashlib.sha256(payload).hexdigest()))
    save(output/'manifest.json',report)
    return data

if __name__=='__main__':
    files=prepare();print('Prepared',len(files),'original-model chest inputs; no PMX or texture changes.')
