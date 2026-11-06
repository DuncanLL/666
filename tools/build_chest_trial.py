"""Rebuild the trial with private tools; never installs, starts games or uploads."""
from pathlib import Path
import argparse,json,shutil,subprocess,sys
from common import ROOT,BUILD,settings,save,sha
def run(*args):subprocess.run([sys.executable,'-X','utf8',*map(str,args)],cwd=ROOT,check=True)
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,default=BUILD/'workshop/chest-trial/R116');a=p.parse_args()
    c=settings();baseline=json.loads((ROOT/'provenance/baseline.json').read_text('utf8'))
    run('tools/build_native.py');run('experiments/softbody/build_preview.py')
    files={name:Path(c['native_source'])/name for name in baseline['native_hashes']}
    files.update({'AimohMmdUpstream.dll':BUILD/'native/AimohMmdUpstream.dll','AimohChestSoftBody.dll':BUILD/'softbody/AimohSoftBodyPreview.dll'})
    candidate=BUILD/'native/game-candidate.json'
    save(candidate,dict(native_version='Aimoh-Upstream-1/skeleton-eval1-softbody1',files={name:dict(path=str(path),sha256=sha(path))for name,path in files.items()}))
    run('tools/prepare_chest_labels.py');run('tools/build.py','--native-candidate',candidate)
    if a.output.exists():
        mod=a.output/'Contents/mods/AimohPmxPreview';assert (a.output/'workshop.txt').is_file()
        shutil.copytree(ROOT/'game/mod',mod,dirs_exist_ok=True);(mod/'common/.gitkeep').unlink(missing_ok=True)
        shutil.copytree(BUILD/'generated-mod',mod,dirs_exist_ok=True)
        shutil.copy2(BUILD/'AimohPMXPreview.jar',mod/'42/media/java/client/AimohPMXPreview.jar')
        run('tools/package_workshop.py','--output',a.output,'--verify-existing')
    else:run('tools/package_workshop.py','--output',a.output)
