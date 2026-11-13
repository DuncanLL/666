"""Check current material programs and the reported NVIDIA varying collision, without game startup."""
from pathlib import Path
import argparse,json,os,subprocess,zipfile
from common import ROOT,BUILD,settings,environment,save,sha
from build import compile_java

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--jar',type=Path,default=BUILD/'render-support/AimohGF2Eclipse.jar');p.add_argument('--tag',default='fixed');p.add_argument('--pmx-reader',action='store_true');a=p.parse_args()
    assert a.tag.isidentifier()
    c=settings();game=Path(c['game_root']);out=BUILD/'shader-link-fix'/a.tag;out.mkdir(parents=True,exist_ok=True)
    media=Path(c['asset_root'])/'42/media';data=json.loads((media/'gfl2_rendering/characters.json').read_text('utf8'))['characters']
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
    sources=[ROOT/'tools/java/OriginalStageLinkCheck.java']
    if a.pmx_reader:sources.append(ROOT/'tools/java/DeformedMotionCheck.java')
    compile_java(c,sources,out/'classes',cp)
    agent=[];main=['OriginalStageLinkCheck']
    if a.pmx_reader:
        file=out/'pmx-reader-agent.jar'
        with zipfile.ZipFile(file,'w')as z:
            z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nPremain-Class: DeformedMotionCheck\nCan-Retransform-Classes: true\nCan-Redefine-Classes: true\n\n')
            for f in(out/'classes').rglob('*.class'):z.write(f,f.relative_to(out/'classes').as_posix())
        agent=['-javaagent:'+str(file)];main=['DeformedMotionCheck','--delegate','OriginalStageLinkCheck']
    cmd=[str(game/'jre64/bin/java.exe'),'--enable-native-access=ALL-UNNAMED','-Djava.io.tmpdir='+str(BUILD/'tmp'),
         *agent,'-cp',';'.join(map(str,[out/'classes',*cp])),*main,str(out/'jobs.json'),str(out)]
    with (out/'run.log').open('w',encoding='utf8')as log:r=subprocess.run(cmd,env=environment(),cwd=game,stdout=log,stderr=subprocess.STDOUT,timeout=180)
    if r.returncode:print((out/'run.log').read_text('utf8',errors='replace')[-4000:]);r.check_returncode()
    result=json.loads((out/'result.json').read_text('utf8'));result['addon_sha256']=sha(a.jar);result['normal_pmx_shader_reader']=a.pmx_reader;save(out/'result.json',result);print(json.dumps(result,ensure_ascii=False))
