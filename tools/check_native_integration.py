"""Run the installed integration audit with the packaged native integrity check."""
from pathlib import Path
import hashlib,json,runpy,sys,zipfile
from common import settings

def packaged_native(jar):
    prefix='gfl2/runtime/native/windows-x64/'
    with zipfile.ZipFile(jar) as archive:
        loader='gfl2/runtime/NativeBundle.class'
        if loader not in archive.namelist():raise ValueError('Packaged native loader missing')
        index=dict(line.split('=',1)for line in archive.read(prefix+'index.properties').decode('ascii').splitlines()if '='in line)
        names=index['files'].split(',');hashes={};records={}
        if not names or len(set(names))!=len(names) or 'GFL2MmdEngine.dll'not in names:raise ValueError('Packaged native index incomplete')
        for name in names:
            if Path(name).name!=name or not name.endswith('.dll'):raise ValueError('Invalid packaged native name')
            payload=archive.read(prefix+name);actual=hashlib.sha256(payload).hexdigest()
            if actual!=index.get(name):raise ValueError('Packaged native checksum differs: '+name)
            hashes[name]=actual;records[name]=dict(path=str(jar)+'!/'+prefix+name,bytes=len(payload),sha256=actual,storage='jar')
        if hashlib.sha256(json.dumps(hashes,sort_keys=True).encode()).hexdigest()!=index['bundle']:raise ValueError('Packaged native bundle identity differs')
        return records

if __name__=='__main__':
    cfg=settings();scripts=Path.home()/'.codex/skills/pz-pmx-eclipse/scripts'
    sys.path[:0]=[str(scripts),cfg['python_dependencies']]
    if cfg.get('pmx_reader_scripts'):sys.path.insert(1,cfg['pmx_reader_scripts'])
    import inspect_pmx
    original_inspect=inspect_pmx.inspect
    def inspect(mod,project=None,verify_models=False):
        result=original_inspect(mod,project,verify_models)
        from integration_contract import mod_paths
        java=mod_paths(mod)['media']/'java/client';native=java/'native/GFL2MmdEngine.dll'
        if not native.is_file() and (java/'GFL2Exilium.jar').is_file():
            try:records=packaged_native(java/'GFL2Exilium.jar')
            except (ValueError,KeyError,zipfile.BadZipFile)as error:
                result['errors'].append(dict(kind='invalid_packaged_runtime',detail=str(error)))
            else:
                result['errors']=[row for row in result['errors']if not(row.get('kind')=='missing_runtime'and row.get('path')==str(native))]
                result['runtime']['native']=records['GFL2MmdEngine.dll'];result['runtime']['packaged_native_files']=records
        return result
    inspect_pmx.inspect=inspect
    runpy.run_path(str(scripts/'check_integration.py'),run_name='__main__')
