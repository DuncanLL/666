"""Reuse the shared PMX/resource audit with the Native-only addon entry contract.

The installed skill checker still requires two Iris-only classes/hooks. Native
uses NativeHooks instead; all source, texture and topology checks stay intact.
"""
from pathlib import Path
import hashlib,json,runpy,sys,types,zipfile
from common import settings

def packaged_native(jar):
    prefix='aimoh/pmxpreview/native/windows-x64/'
    with zipfile.ZipFile(jar) as archive:
        loader='aimoh/pmxpreview/NativeBundle.class'
        if loader not in archive.namelist():raise ValueError('Packaged native loader missing')
        index=dict(line.split('=',1)for line in archive.read(prefix+'index.properties').decode('ascii').splitlines()if '='in line)
        names=index['files'].split(',');hashes={};records={}
        if not names or len(set(names))!=len(names) or 'AimohMmdUpstream.dll'not in names:raise ValueError('Packaged native index incomplete')
        for name in names:
            if Path(name).name!=name or not name.endswith('.dll'):raise ValueError('Invalid packaged native name')
            payload=archive.read(prefix+name);actual=hashlib.sha256(payload).hexdigest()
            if actual!=index.get(name):raise ValueError('Packaged native checksum differs: '+name)
            hashes[name]=actual;records[name]=dict(path=str(jar)+'!/'+prefix+name,bytes=len(payload),sha256=actual,storage='jar')
        if hashlib.sha256(json.dumps(hashes,sort_keys=True).encode()).hexdigest()!=index['bundle']:raise ValueError('Packaged native bundle identity differs')
        return records

if __name__=='__main__':
    cfg=settings();scripts=Path.home()/'.codex/skills/pz-pmx-eclipse/scripts'
    sys.path[:0]=[str(scripts),cfg['pmx_reader_scripts'],cfg['python_dependencies']]
    import inspect_pmx
    original_inspect=inspect_pmx.inspect
    def inspect(mod,project=None,verify_models=False):
        result=original_inspect(mod,project,verify_models)
        from integration_contract import mod_paths
        java=mod_paths(mod)['media']/'java/client';native=java/'native/AimohMmdUpstream.dll'
        if not native.is_file() and (java/'AimohPMXPreview.jar').is_file():
            try:records=packaged_native(java/'AimohPMXPreview.jar')
            except (ValueError,KeyError,zipfile.BadZipFile)as error:
                result['errors'].append(dict(kind='invalid_packaged_runtime',detail=str(error)))
            else:
                result['errors']=[row for row in result['errors']if not(row.get('kind')=='missing_runtime'and row.get('path')==str(native))]
                result['runtime']['native']=records['AimohMmdUpstream.dll'];result['runtime']['packaged_native_files']=records
        return result
    inspect_pmx.inspect=inspect
    source=(scripts/'check_pair.py').read_text('utf8')
    needle="required=['Gf2PackData','Gf2SourceHost','Gf2Runtime','Gf2PackMaterials','Gf2OriginalProgram','Gf2FrameInputs','Gf2RuntimeQueue','Gf2DepthStencil']"
    assert source.count(needle)==1
    source=source.replace(needle,needle+"\n    native_only=manifest.get('addon_runtime',{}).get('backend')=='native'\n    if native_only:required=[name for name in required if name not in ['Gf2Runtime','Gf2RuntimeQueue']]")
    needle="declared_hooks=all(name in transformer for name in [b'sourceEarly',b'beforeSourcePass',b'aimoh/motion/PmxOriginalInputs',b'viewpoint.render.AimohGf2SourceHost'])"
    assert source.count(needle)==1
    source=source.replace(needle,"declared_hooks=all(name in transformer for name in ([b'aimoh/motion/PmxOriginalInputs',b'viewpoint.render.AimohGf2SourceHost'] if native_only else [b'sourceEarly',b'beforeSourcePass',b'aimoh/motion/PmxOriginalInputs',b'viewpoint.render.AimohGf2SourceHost']))")
    module=types.ModuleType('check_pair');module.__file__=str(scripts/'check_pair.py')
    exec(compile(source,module.__file__,'exec'),module.__dict__);sys.modules['check_pair']=module
    runpy.run_path(str(scripts/'check_integration.py'),run_name='__main__')
