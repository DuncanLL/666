"""Read back local and Workshop-author runtime copies; never install or start a game."""
from pathlib import Path
import argparse
from common import BUILD,settings,sha,save

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--jar',type=Path,default=BUILD/'AimohPMXPreview.jar');args=parser.parse_args()
    config=settings();expected=sha(args.jar)
    roots=[('local',Path(p))for p in config['game_install_roots']]+[('workshop-author',Path(p)/'Contents/mods/AimohPmxPreview')for p in config['workshop_targets']]
    rows=[]
    for kind,root in roots:
        jar=root/'42/media/java/client/AimohPMXPreview.jar';info=root/'42/mod.info'
        version=next((line.split('=',1)[1]for line in info.read_text('utf8').splitlines()if line.startswith('modversion=')),None)if info.is_file()else None
        fingerprint=sha(jar)if jar.is_file()else None
        rows.append(dict(kind=kind,root=str(root.resolve()),version=version,sha256=fingerprint,matches=fingerprint==expected))
        print(kind,root,version,'MATCH'if fingerprint==expected else'MISMATCH',flush=True)
    save(BUILD/'runtime-locations.json',dict(expected_sha256=expected,targets=rows,game_started=False,files_modified=False))
    if not all(row['matches']for row in rows):raise SystemExit('Runtime copies differ; check the latest game log for the actually loaded path.')
