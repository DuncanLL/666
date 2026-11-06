"""Check current material programs and the reported NVIDIA varying collision, without game startup."""
from pathlib import Path
import argparse,json,os,subprocess
from common import ROOT,BUILD,settings,environment,save,sha
from build import compile_java

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--jar',type=Path,default=BUILD/'render-support/AimohGF2Eclipse.jar');p.add_argument('--tag',default='fixed');a=p.parse_args()
    assert a.tag.isidentifier()
    c=settings();game=Path(c['game_root']);out=BUILD/'shader-link-fix'/a.tag;out.mkdir(parents=True,exist_ok=True)
    media=Path(c['asset_root'])/'42/media';data=json.loads((media/'aimoh_gf2/characters.json').read_text('utf8'))['characters']
    def resource(row):
        assert row['storage']=='mod';path=(media/('aimoh_'+row['path'])).resolve();assert path.is_relative_to(media.resolve())and path.is_file();assert sha(path)==row['sha256'];return str(path)
    groups={}
    for row in data['programs']:groups.setdefault((row['material'],row['pass']),{})[row['stage']]=row['programs'][0]['sha256']
    jobs={}
    for (material,kind),row in groups.items():
        vs,fs=row['progVertex'],row['progFragment']
        for early in [False,True]if kind=='GFCharForward'else[False]:
            entry=data['early_fragments'][fs]if early else data['glsl'][fs]
            jobs.setdefault((vs,fs,early),dict(vs=vs,fs=fs,kind=kind+('/early'if early else''),vertex=resource(data['glsl'][vs]),fragment=resource(entry)))
    values=sorted(jobs.values(),key=lambda j:(not(j['vs'].startswith('0625dcfb5b91')and j['fs'].startswith('4b1d0100e82b')),j['vs'],j['fs'],j['kind']))
    assert values[0]['vs'].startswith('0625dcfb5b91')and values[0]['fs'].startswith('4b1d0100e82b')
    save(out/'jobs.json',values)
    cp=[a.jar.resolve(),Path(c['viewpoint_jar']),Path(c['zombiebuddy_jar']),BUILD/'AimohPMXPreview.jar',game,*game.glob('*.jar')]
    compile_java(c,[ROOT/'tools/java/OriginalStageLinkCheck.java'],out/'classes',cp)
    cmd=[str(game/'jre64/bin/java.exe'),'--enable-native-access=ALL-UNNAMED','-Djava.io.tmpdir='+str(BUILD/'tmp'),
         '-cp',';'.join(map(str,[out/'classes',*cp])),'OriginalStageLinkCheck',str(out/'jobs.json'),str(out)]
    with (out/'run.log').open('w',encoding='utf8')as log:r=subprocess.run(cmd,env=environment(),cwd=game,stdout=log,stderr=subprocess.STDOUT,timeout=180)
    if r.returncode:print((out/'run.log').read_text('utf8',errors='replace')[-4000:]);r.check_returncode()
    result=json.loads((out/'result.json').read_text('utf8'));result['addon_sha256']=sha(a.jar);save(out/'result.json',result);print(json.dumps(result,ensure_ascii=False))
