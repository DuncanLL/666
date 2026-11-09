"""Build the Eclipse distribution on the verified official Viewpoint host."""
from pathlib import Path
import argparse,hashlib,io,os,shutil,zipfile
from common import ROOT,BUILD,settings,sha,save
from build import compile_java
from build_render_support import check_glsl_names

SHADER_PAYLOAD='aimoh/gf2/bridge/Aimoh-Eclipse-GF2.zip'
PACK_SHADERS='Eclipse-Shader-Unstable/shaders/'
SHADERS=PACK_SHADERS+'dimensions/'
MOTION_PASS=SHADERS+'composite6.fsh'
FOG_FILTER_PASS=SHADERS+'composite4.fsh'
FOG_RAY_PASS=SHADERS+'composite3.fsh'
OLD_MOTION=b""" ivec2 at=ivec2(closestToCamera.xy/texelSize);
 vec2 actorMotion=texelFetch(vpDanMotionTex,at,0).rg;
 if(texelFetch(colortex17,at,0).r>.5 && all(lessThan(abs(actorMotion),vec2(65000))))
     velocity=-actorMotion;
"""

def patch_shaders(payload,patches):
    result=io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(payload))as old,zipfile.ZipFile(result,'w',zipfile.ZIP_DEFLATED)as new:
        for name in patches:
            assert old.namelist().count(name)==1,'Eclipse shader is absent or ambiguous: '+name
        for entry in old.infolist():
            data=old.read(entry)
            for before,after in patches.get(entry.filename,()):
                assert data.count(before)==1,'Eclipse shader edit point changed: '+entry.filename
                data=data.replace(before,after)
            check_glsl_names(entry.filename,data)
            new.writestr(entry,data)
    return result.getvalue()

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--workshop',type=Path);parser.add_argument('--metadata-from',type=Path);args=parser.parse_args()
    cfg=settings();import json
    origin=json.loads((ROOT/'provenance/eclipse-outline.json').read_text('utf8'))
    reference=Path(cfg['eclipse_support_reference_jar']);host=Path(cfg['viewpoint_jar']);game=Path(cfg['game_root'])
    assert sha(reference)==origin['reference_jar_sha256'],'Preserved Eclipse baseline differs'
    assert sha(host)==origin['official_host_sha256'],'Unsupported official VP build'
    out=BUILD/'eclipse-support';out.mkdir(exist_ok=True)
    shared=[ROOT/'render-support/java'/name for name in origin['shared_sources']]
    sources=[*sorted((ROOT/'render-support/eclipse/java').rglob('*.java')),*shared]
    classes=out/'classes-outline';compile_java(cfg,sources,classes,[reference,host,cfg['zombiebuddy_jar'],cfg['reference_jar'],*game.glob('*.jar')])
    replacements={p.relative_to(classes).as_posix():p.read_bytes()for p in classes.rglob('*.class')}
    jar=out/'AimohGF2Eclipse.jar';temporary=jar.with_suffix('.jar.writing')
    motion=ROOT/'render-support/eclipse/shaders/actor-motion.glsl'
    options=((b'characterSurfaceDetail',0,0,100),(b'characterSkinGloss',0,0,100),
             (b'characterOutlineWidth',100,75,135))
    controls=b' '.join(name for name,_,_,_ in options)
    def decimal(hundredths):
        return f'{hundredths//100}.{hundredths%100:02d}'.encode('ascii')
    defines=b''.join(b'#define '+name+b' '+decimal(default)+b' // ['+
                     b' '.join(map(decimal,range(first,last+1)))+b']\n'
                     for name,default,first,last in options)
    option_labels={
        'en_us':(
            'R116 Character Effects',
            'Surface Detail', 'Strengthen existing skin normal detail. 0 keeps the original look.',
            'Skin Gloss', 'Tighten existing skin highlights. 0 keeps the original look.',
            'Character Outline Width', 'Scale the original character outline width. 1 keeps the original width.',
        ),
        'zh_cn':(
            'R116 人物效果',
            '体表细节增强', '强化原皮肤材质的法线起伏。0 保持原效果。',
            '皮肤光泽增强', '收紧原皮肤高光，增强曲面反射感。0 保持原效果。',
            '人物描边粗细', '缩放人物原材质的描边宽度。1 保持原效果。',
        ),
    }
    patches={
        PACK_SHADERS+'gf2/r2_settings.glsl':[
            (b'#include "/danrp/settings.glsl"\n',b'#include "/danrp/settings.glsl"\n'+defines),
        ],
        PACK_SHADERS+'shaders.properties':[
            (b'\nsliders = ',b'\nsliders = '+controls+b' '),
            (b'\nscreen.columns=2\n',b'\nscreen.R116_CHARACTER = '+controls+b'\nscreen.columns=2\n'),
            (b'\n[Misc_Settings] [Mod_support] \\\n',b'\n[Misc_Settings] [Mod_support] \\\n[R116_CHARACTER] <empty>\n'),
        ],
        MOTION_PASS:[
            (OLD_MOTION,motion.read_bytes().replace(b'\r\n',b'\n')),
            (b'abs(clampedframeHistory - frameHistory)/clampedframeHistory',
             b'abs(clampedframeHistory - frameHistory)/max(clampedframeHistory,vec3(1e-6))'),
        ],
        FOG_FILTER_PASS:[
            (b'  for(int i = 0; i < samples; i++) {',
             b'''  ivec2 depthLimit = textureSize(depth,0)-1;
  ivec2 colorSize = textureSize(colortex,0);
  // The fog pass writes only the lower-left portion of its full-size texture.
  ivec2 fogLimit = clamp(ivec2(ceil(vec2(colorSize)*VL_RENDER_SCALE)),ivec2(1),colorSize)-1;
  for(int i = 0; i < samples; i++) {
    ivec2 offset = OFFSET[i] + UV_NOISE;
    ivec2 depthAt = clamp(UV_DEPTH + offset*SCALE,ivec2(0),depthLimit);
    ivec2 fogAt = clamp(UV_COLOR + offset,ivec2(0),fogLimit);'''),
            (b'UV_DEPTH + (OFFSET[i] + UV_NOISE) * SCALE,0)[behindTranslucents]',b'depthAt,0)[behindTranslucents]'),
            (b'UV_DEPTH + (OFFSET[i] + UV_NOISE) * SCALE, 0).r',b'depthAt,0).r'),
            (b'UV_COLOR + OFFSET[i] + UV_NOISE, 0)',b'fogAt,0)'),
        ],
        FOG_RAY_PASS:[
            (b'vec2 tc = floor(gl_FragCoord.xy)/VL_RENDER_SCALE*texelSize + texelSize*0.5;',
             b'vec2 tc = min(floor(gl_FragCoord.xy)/VL_RENDER_SCALE*texelSize + texelSize*0.5, vec2(1.0)-texelSize*0.5);'),
        ],
    }
    for language,(screen,detail,detail_help,gloss,gloss_help,outline,outline_help) in option_labels.items():
        labels=(f'screen.R116_CHARACTER = {screen}\n'
                f'option.characterSurfaceDetail = {detail}\n'
                f'option.characterSurfaceDetail.comment = {detail_help}\n'
                f'option.characterSkinGloss = {gloss}\n'
                f'option.characterSkinGloss.comment = {gloss_help}\n'
                f'option.characterOutlineWidth = {outline}\n'
                f'option.characterOutlineWidth.comment = {outline_help}\n').encode('utf8')
        first=b'option.SHADER_VERSION_LABEL = '
        patches[PACK_SHADERS+'lang/'+language+'.lang']=[(first,labels+first)]
    with zipfile.ZipFile(host)as z:stock=set(z.namelist())
    assert not stock.intersection(replacements),'Official class collision'
    with zipfile.ZipFile(reference)as old,zipfile.ZipFile(temporary,'w',zipfile.ZIP_DEFLATED)as new:
        for name in old.namelist():
            if name not in replacements:
                raw=old.read(name)
                if name==SHADER_PAYLOAD:raw=patch_shaders(raw,patches)
                new.writestr(old.getinfo(name),raw)
        for name,data in sorted(replacements.items()):
            info=zipfile.ZipInfo(name,date_time=(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;new.writestr(info,data)
    with zipfile.ZipFile(reference)as old,zipfile.ZipFile(temporary)as new:
        assert new.testzip()is None
        for name in old.namelist():
            if name!=SHADER_PAYLOAD and name not in replacements:assert old.read(name)==new.read(name),'Unrelated Eclipse payload changed: '+name
        with zipfile.ZipFile(io.BytesIO(old.read(SHADER_PAYLOAD)))as old_pack,zipfile.ZipFile(io.BytesIO(new.read(SHADER_PAYLOAD)))as new_pack:
            assert old_pack.namelist()==new_pack.namelist(),'Eclipse shader pack entries changed'
            assert {name for name in old_pack.namelist() if old_pack.read(name)!=new_pack.read(name)}==patches.keys()
    os.replace(temporary,jar)
    with zipfile.ZipFile(jar)as archive:
        shader_sha=hashlib.sha256(archive.read(SHADER_PAYLOAD)).hexdigest()
    save(out/'build.json',dict(status='ECLIPSE_CANDIDATE_NOT_INSTALLED',backend='eclipse',version=origin['version'],jar=str(jar),jar_sha256=sha(jar),base_jar_sha256=sha(reference),host_sha256=sha(host),replaced_entries=sorted(replacements),source_hashes={p.relative_to(ROOT).as_posix():sha(p)for p in [*sources,motion,ROOT/'tools/build_eclipse_support.py',ROOT/'provenance/eclipse-outline.json']},shader_payload=SHADER_PAYLOAD,shader_sha256=shader_sha,shader_entries_changed=sorted(patches),official_host_unchanged=True,game_started=False,installed=False,uploaded=False))
    if args.workshop:
        assert args.metadata_from is not None,'Preserve the existing Workshop metadata with --metadata-from'
        target=args.workshop.resolve();assert not target.exists(),'Preserve existing candidates; choose a new output'
        mod=target/'Contents/mods/AimohGF2Eclipse';shutil.copytree(ROOT/'render-support/eclipse/mod',mod)
        (mod/'common/.gitkeep').unlink(missing_ok=True);dest=mod/'42/media/java/client/AimohGF2Eclipse.jar';dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(jar,dest)
        for name in ('workshop.txt','preview.png'):shutil.copy2(args.metadata_from/name,target/name)
        assert sha(dest)==sha(jar)
        save(out/'workshop.json',dict(status='ECLIPSE_CANDIDATE_ONLY',path=str(target),jar_sha256=sha(dest),installed=False,uploaded=False))
    print('Built preserved Eclipse candidate: '+str(jar),flush=True)
