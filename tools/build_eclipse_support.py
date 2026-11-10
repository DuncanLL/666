"""Build the Eclipse distribution on the verified official Viewpoint host."""
from pathlib import Path
import argparse,hashlib,io,json,os,re,shutil,zipfile
from common import ROOT,BUILD,settings,sha,save
from build import compile_java
from build_render_support import check_glsl_names,release_bootstrap

SHADER_PAYLOAD='aimoh/gf2/bridge/Aimoh-Eclipse-GF2.zip'
PACK_SHADERS='Eclipse-Shader-Unstable/shaders/'
SHADERS=PACK_SHADERS+'dimensions/'
MOTION_PASS=SHADERS+'composite6.fsh'
FOG_FILTER_PASS=SHADERS+'composite4.fsh'
FOG_RAY_PASS=SHADERS+'composite3.fsh'
GF2_MANIFEST=PACK_SHADERS+'gf2/manifest.json'
ORPHAN_SNAPSHOT='viewpoint/render/AimohGF2Services$Snapshot.class'
MC_TREES=('world-1/','world1/','world0_with_aether_flag/','world0_with_twilight_forest_flag/',
          'template/','world0/clrwl_')
# Official RC3 has no draw or fallback path to these entries.
MC_PROGRAMS={
    'dh_generic','dh_water','gbuffers_armor_glint','gbuffers_beaconbeam','gbuffers_block_translucent',
    'gbuffers_damagedblock','gbuffers_entities_glowing','gbuffers_hand','gbuffers_hand_water',
    'gbuffers_particles','gbuffers_particles_translucent','gbuffers_spidereyes',
    'gbuffers_terrain_cutout','gbuffers_voxels','physics_ocean','physics_ocean_v2',
    'shadow_entities','shadow_voxels','voxy','voxy_opaque','voxy_translucent',
}
MC_FILES={
    'colorwheel.properties','entity.properties','item.properties','world0/gbuffers_terrain.gsh',
    'dimensions/all_solid.gsh','dimensions/beacon.gsh',
    'dimensions/DH_generic.fsh','dimensions/DH_generic.vsh',
    'dimensions/DH_translucent.fsh','dimensions/DH_translucent.vsh',
    'dimensions/all_vanilla_emissives.fsh','dimensions/all_vanilla_emissives.vsh',
    'dimensions/voxels.fsh','dimensions/voxels.vsh','dimensions/voxels_shadow.fsh','dimensions/voxels_shadow.vsh',
    'dimensions/voxy.json','dimensions/voxy_solid.glsl','dimensions/voxy_translucent.glsl',
}
VP_BLOCKS=b"""# Material aliases queried by Viewpoint 0.1.7-rc3.
block.8=water flowing_water
block.12=short_grass grass
block.13=tall_grass
block.54=fern
block.56=oak_leaves birch_leaves
block.301=glass_pane glass
block.316=white_stained_glass_pane
"""
OLD_MOTION=b""" ivec2 at=ivec2(closestToCamera.xy/texelSize);
 vec2 actorMotion=texelFetch(vpDanMotionTex,at,0).rg;
 if(texelFetch(colortex17,at,0).r>.5 && all(lessThan(abs(actorMotion),vec2(65000))))
     velocity=-actorMotion;
"""

def trim_minecraft(source,patches):
    # The host only chooses the overworld folder. Keep the official material aliases
    # and drop the unused grass/beacon options before Iris scans settings for macros.
    removed=set()
    for name in source.namelist():
        if not name.startswith(PACK_SHADERS):continue
        path=name.removeprefix(PACK_SHADERS)
        if path in MC_FILES or path.startswith(MC_TREES) or (
            path.startswith('world0/') and Path(path).stem in MC_PROGRAMS
        ):
            removed.add(name)
    assert PACK_SHADERS+'world0/gbuffers_terrain.gsh' in removed
    for name,data in (('dimension.properties',b'dimension.world0 = minecraft:overworld *\n'),
                      ('block.properties',VP_BLOCKS)):
        path=PACK_SHADERS+name
        patches[path]=[(source.read(path),data)]
    path=PACK_SHADERS+'lib/settings.glsl'
    settings=source.read(path)
    grass=settings[settings.index(b'#define SHADER_GRASS_SETTING\n'):settings.index(b'#define RAINBOW_SELECT_BOX ')]
    beacon=settings[settings.index(b'#define BEACON_FLOODFILL\n'):settings.index(b'#define LPV_REDSTONE_LIGHTS\n')]
    patches[path]=[(grass,b'#define WAVING_MULTIPART_GRASS\n\n'),(beacon,b'')]
    path=PACK_SHADERS+'shaders.properties'
    properties=source.read(path)
    foliage=properties[properties.index(b'        screen.Foliage = '):properties.index(b'\n\n        screen.AURORA_SETTINGS.columns')]
    patches[path].extend([
        (foliage,b'        screen.Foliage = WAVY_PLANTS WAVY_STRENGTH WAVY_SPEED'),
        (b'                     BEACON_FLOODFILL BEACON_FLOODFILL_BRIGHTNESS \\\n',b''),
        # Both compute entry points have empty bodies when Eclipse LPV is inactive.
        (b'bufferObject.4 = 1024\n',
         b'bufferObject.4 = 1024\n'
         b'program.world0/setup.enabled = LPV_ENABLED && IRIS_FEATURE_CUSTOM_IMAGES\n'
         b'program.world0/composite20.enabled = LPV_ENABLED && IRIS_FEATURE_CUSTOM_IMAGES\n'),
    ])
    return removed

def patch_shaders(payload,patches):
    result=io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(payload))as old,zipfile.ZipFile(result,'w',zipfile.ZIP_DEFLATED)as new:
        removed=trim_minecraft(old,patches)
        bootstrap=old.read(GF2_MANIFEST)
        patches[GF2_MANIFEST]=[(bootstrap,release_bootstrap(bootstrap))]
        for name in patches:
            assert old.namelist().count(name)==1,'Eclipse shader is absent or ambiguous: '+name
        for entry in old.infolist():
            if entry.filename in removed:
                continue
            data=old.read(entry)
            for before,after in patches.get(entry.filename,()):
                assert data.count(before)==1,'Eclipse shader edit point changed: '+entry.filename
                data=data.replace(before,after)
            check_glsl_names(entry.filename,data)
            if entry.filename.endswith(('.glsl','.vsh','.fsh','.gsh','.csh','.tes','.tcs')):
                for include in re.findall(rb'(?m)^\s*#\s*include\s+"/([^"]+)"',data):
                    assert PACK_SHADERS+include.decode('ascii') not in removed,'Retained shader includes removed file: '+entry.filename
            new.writestr(entry,data)
    return result.getvalue(),removed

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--workshop',type=Path);parser.add_argument('--metadata-from',type=Path);args=parser.parse_args()
    cfg=settings()
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
    options=((b'characterSurfaceDetail',0,0,100,1),(b'characterSkinGloss',0,0,100,1),
             (b'characterOutlineWidth',100,75,135,1),(b'characterKeyLightStrength',100,0,1000,5))
    controls=b' '.join(name for name,_,_,_,_ in options)
    def decimal(hundredths):
        return f'{hundredths//100}.{hundredths%100:02d}'.encode('ascii')
    defines=b''.join(b'#define '+name+b' '+decimal(default)+b' // ['+
                     b' '.join(map(decimal,range(first,last+1,step)))+b']\n'
                     for name,default,first,last,step in options)
    option_labels={
        'en_us':(
            'R116 Character Effects',
            'Skin Normal Detail', 'Strengthen surface relief and fine detail from the skin normal map. 0 keeps the original look.',
            'Skin Highlight Tightness', 'Reduce skin roughness to concentrate existing reflections. 0 keeps the original look.',
            'Character Outline Width', 'Scale the original character outline width. 1 keeps the original width.',
            'Character Light Color Calibration', 'Reduce bright light washout on characters. Values above 2 also rebalance daylight and shade colors. 0 disables the correction and 10 is strongest.',
        ),
        'zh_cn':(
            'R116 人物效果',
            '皮肤凹凸细节', '加强原法线的凹凸，例如腹部起伏与皮肤细纹。0 为原效果。',
            '皮肤高光集中度', '降低皮肤材质粗糙度，让原有反光更集中。0 为原效果。',
            '人物描边粗细', '缩放人物原材质的描边宽度。1 保持原效果。',
            '人物光色校准', '减轻强光下人物发白。超过 2 会进一步校准亮面偏黄和阴影偏红。0 关闭，10 最明显。',
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
            (b'#ifdef VP_DANRP_PIPELINE\nuniform sampler2D vpDanMotionTex,colortex17;\n#endif\n#include "/lib/settings.glsl"\n',
             b'#include "/lib/settings.glsl"\n#if defined TAA && defined VP_DANRP_PIPELINE\nuniform sampler2D vpDanMotionTex,colortex17;\n#endif\n'),
            (b'#ifdef VP_DANRP_PIPELINE\n'+OLD_MOTION,
             b'#if defined TAA && defined VP_DANRP_PIPELINE\n'+motion.read_bytes().replace(b'\r\n',b'\n')),
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
    for language,(screen,detail,detail_help,gloss,gloss_help,outline,outline_help,key_light,key_help) in option_labels.items():
        labels=(f'screen.R116_CHARACTER = {screen}\n'
                f'option.characterSurfaceDetail = {detail}\n'
                f'option.characterSurfaceDetail.comment = {detail_help}\n'
                f'option.characterSkinGloss = {gloss}\n'
                f'option.characterSkinGloss.comment = {gloss_help}\n'
                f'option.characterOutlineWidth = {outline}\n'
                f'option.characterOutlineWidth.comment = {outline_help}\n'
                f'option.characterKeyLightStrength = {key_light}\n'
                f'option.characterKeyLightStrength.comment = {key_help}\n').encode('utf8')
        first=b'option.SHADER_VERSION_LABEL = '
        patches[PACK_SHADERS+'lang/'+language+'.lang']=[(first,labels+first)]
    with zipfile.ZipFile(host)as z:stock=set(z.namelist())
    assert not stock.intersection(replacements),'Official class collision'
    with zipfile.ZipFile(reference)as old,zipfile.ZipFile(temporary,'w',zipfile.ZIP_DEFLATED)as new:
        assert ORPHAN_SNAPSHOT in old.namelist() and ORPHAN_SNAPSHOT not in replacements
        shader,removed=patch_shaders(old.read(SHADER_PAYLOAD),patches)
        for name in old.namelist():
            if name not in replacements and name!=ORPHAN_SNAPSHOT:
                raw=shader if name==SHADER_PAYLOAD else old.read(name)
                new.writestr(old.getinfo(name),raw)
        for name,data in sorted(replacements.items()):
            info=zipfile.ZipInfo(name,date_time=(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;new.writestr(info,data)
    with zipfile.ZipFile(reference)as old,zipfile.ZipFile(temporary)as new:
        assert new.testzip()is None and ORPHAN_SNAPSHOT not in new.namelist()
        orphan=ORPHAN_SNAPSHOT.removesuffix('.class').encode()
        assert all(orphan not in new.read(name) and orphan.replace(b'/',b'.') not in new.read(name)
                   for name in new.namelist() if name.endswith('.class')),'Eclipse snapshot is still referenced'
        for name in old.namelist():
            if name not in (SHADER_PAYLOAD,ORPHAN_SNAPSHOT) and name not in replacements:
                assert old.read(name)==new.read(name),'Unrelated Eclipse payload changed: '+name
        with zipfile.ZipFile(io.BytesIO(old.read(SHADER_PAYLOAD)))as old_pack,zipfile.ZipFile(io.BytesIO(new.read(SHADER_PAYLOAD)))as new_pack:
            assert removed<=set(old_pack.namelist()),'Expected terrain geometry stage missing from the reference'
            assert [name for name in old_pack.namelist() if name not in removed]==new_pack.namelist(),'Eclipse shader pack entries changed'
            assert {name for name in new_pack.namelist() if old_pack.read(name)!=new_pack.read(name)}==patches.keys()
    os.replace(temporary,jar)
    with zipfile.ZipFile(jar)as archive:
        shader_sha=hashlib.sha256(archive.read(SHADER_PAYLOAD)).hexdigest()
    save(out/'build.json',dict(status='ECLIPSE_CANDIDATE_NOT_INSTALLED',backend='eclipse',version=origin['version'],jar=str(jar),jar_sha256=sha(jar),base_jar_sha256=sha(reference),host_sha256=sha(host),replaced_entries=sorted(replacements),source_hashes={p.relative_to(ROOT).as_posix():sha(p)for p in [*sources,motion,ROOT/'tools/build_eclipse_support.py',ROOT/'tools/build_render_support.py',ROOT/'provenance/eclipse-outline.json']},shader_payload=SHADER_PAYLOAD,shader_sha256=shader_sha,shader_entries_changed=sorted(patches),retired_jar_entries=[ORPHAN_SNAPSHOT],official_host_unchanged=True,game_started=False,installed=False,uploaded=False))
    if args.workshop:
        assert args.metadata_from is not None,'Preserve the existing Workshop metadata with --metadata-from'
        target=args.workshop.resolve();assert not target.exists(),'Preserve existing candidates; choose a new output'
        mod=target/'Contents/mods/AimohGF2Eclipse';shutil.copytree(ROOT/'render-support/eclipse/mod',mod)
        (mod/'common/.gitkeep').unlink(missing_ok=True);dest=mod/'42/media/java/client/AimohGF2Eclipse.jar';dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(jar,dest)
        for name in ('workshop.txt','preview.png'):shutil.copy2(args.metadata_from/name,target/name)
        assert sha(dest)==sha(jar)
        save(out/'workshop.json',dict(status='ECLIPSE_CANDIDATE_ONLY',path=str(target),jar_sha256=sha(dest),installed=False,uploaded=False))
    print('Built preserved Eclipse candidate: '+str(jar),flush=True)
