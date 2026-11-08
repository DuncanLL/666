"""Reuse the shared PMX/resource audit with the Native-only addon entry contract.

The installed skill checker still requires two Iris-only classes/hooks. Native
uses NativeHooks instead; all source, texture and topology checks stay intact.
"""
from pathlib import Path
import runpy,sys,types
from common import settings

if __name__=='__main__':
    cfg=settings();scripts=Path.home()/'.codex/skills/pz-pmx-eclipse/scripts'
    sys.path[:0]=[str(scripts),cfg['pmx_reader_scripts'],cfg['python_dependencies']]
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
