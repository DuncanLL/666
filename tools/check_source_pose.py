"""Trace the actual installed pose modifier and test the common animation boundary through real advice."""
from pathlib import Path
import json,os,sys,zipfile,time,math
from common import ROOT,BUILD,settings,save,sha
from build import compile_java

c=settings();game=Path(c['game_root']);out=BUILD/'source-pose-0.3.8';out.mkdir(exist_ok=True);jar=BUILD/'AimohPMXPreview.jar'
os.environ['PATH']=str(game/'jre64/bin')+os.pathsep+os.environ['PATH']
dll_directory=os.add_dll_directory(str(game/'jre64/bin'))
ads=game.parent.parent/'workshop/content/108600/3812612054/mods/ProjectViewpointADS/42/media/java/client/ProjectViewpointADS.jar'
assert ads.is_file(),'Actual reported modifier required for writer reproduction; do not substitute its implementation'
cp=[jar,Path(c['viewpoint_jar']),Path(c['zombiebuddy_jar']),ads,*game.glob('*.jar')]
classes=out/'classes';compile_java(c,[ROOT/'tools/java/SourcePoseFixture.java',ROOT/'tools/java/DeformedMotionCheck.java'],classes,cp)
agent=out/'agent.jar'
with zipfile.ZipFile(agent,'w')as z:
    z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nPremain-Class: DeformedMotionCheck\nCan-Retransform-Classes: true\nCan-Redefine-Classes: true\n\n')
    for file in classes.rglob('*.class'):z.write(file,file.relative_to(classes).as_posix())
sys.path[:0]=[c['python_dependencies'],c['pmx_reader_scripts']]
import jpype,numpy as np
original_start=jpype.startJVM
jpype.startJVM=lambda *args,**kwargs:original_start(*args,'-javaagent:'+str(agent),**kwargs)
from native_session import NativeSession
cfg=json.loads(Path('F:/CodexData/2026-10-21/f-laiya/work/transfer/config/native-environment-v1.json').read_text('utf8'))
cfg.update(game_dir=str(game),python_deps=c['python_dependencies'],skeleton_source=str(game/'media/models_X/Skinned/MaleBody.x'),override_roots=[],additional_jars=[str(agent),*map(str,cp)])
os.environ['TEMP']=os.environ['TMP']=str(out);s=NativeSession(cfg,out/('source-'+str(os.getpid())));J=s.J
try:
    os.chdir(game);J('zombie.ZomboidFileSystem').instance.setCacheDir(str(out/'User'));J('zombie.ZomboidFileSystem').instance.init()
    tracks=s.tracks([dict(path=str(game/'media/anims_X/Bob/Bob_IdleNoAim1Hand.x'),loop=True)])
    cls=J('pvads.ADS').class_;fixture=J('aimoh.pmxpreview.SourcePoseFixture');actor=fixture.owner(s.player)
    def field(name):f=cls.getDeclaredField(name);f.setAccessible(True);return f
    def method(name):m=next(m for m in cls.getDeclaredMethods()if str(m.getName())==name);m.setAccessible(True);return m
    def fresh():
        s.player.setIsoGameCharacter(None);s.sample(tracks,.25,[1.]);s.player.setIsoGameCharacter(actor)
        fixture.evaluateAnimation(s.player)
        field('armFilt').setBoolean(None,False);field('raiseHide').setBoolean(None,True)
    def state():
        bad=[];singular=[]
        for i in range(s.player.getModelTransformsCount()):
            m=fixture.raw(s.player,i);v=[getattr(m,'m'+str(r)+str(col))for r in range(4)for col in range(4)]
            if any(not math.isfinite(x)for x in v):bad.append(i)
            elif m.determinant()==0:singular.append(i)
        return dict(nonfinite=bad,singular=singular)
    fresh();method('bindBones').invoke(None,s.player);fixture.watched(actor,True)
    clips=[game/'media/anims_X/Bob'/(name+'.x')for name in ['Bob_Idle','Bob_Walk','Bob_Run','Bob_IdleLowSneak','Bob_WalkSneak','Bob_IdleAimRifle','Bob_IdleAimHandgun','Bob_IdleToAim_Rifle','Bob_AimToIdle_Rifle','Bob_WalkAimRifle','Bob_RunRifle','Bob_SitGround_Idle','Bob_ActionToSitIdle_rifle','Bob_ClimbFence_Loop','Bob_Attack1Hand01_Hit','Bob_AttackBat']]
    dance=Path(c['asset_root']).parents[0]/'TrueActionsDancing/common/media/anims_X/ordinary/BobTA_YMCA.fbx'
    if dance.is_file():clips.append(dance)
    clip_tracks={str(file):s.tracks([dict(path=str(file),loop=True)])for file in clips}
    def action_samples():
        samples={}
        for file,track in clip_tracks.items():
            for at in [.0,.4,.8]:
                s.player.setIsoGameCharacter(None);s.sample(track,at,[1.]);s.player.setIsoGameCharacter(actor);fixture.evaluateAnimation(s.player)
                samples[file+'@'+str(at)]=np.asarray(J('aimoh.motion.PzPoseCapture').capture(s.player,s.player.getModelTransformsCount())).copy()
        return samples
    actions_before=action_samples();fresh()
    Instance=J('zombie.core.skinnedmodel.model.ModelInstance');parent=Instance();weapon=Instance();weapon.parent=parent;weapon.parentBoneName='Bip01_Prop1';field('e').setFloat(None,.5)
    clean=state();method('raiseHideArms').invoke(None,s.player);hidden_before=state();method('steadyArms').invoke(None,s.player,weapon,s.jpype.JFloat(.016));broken=state()
    assert not clean['nonfinite']and not clean['singular']and hidden_before['singular']and broken['nonfinite'],(clean,hidden_before,broken)
    rejected=False
    try:J('aimoh.motion.PzPoseCapture').capture(s.player,s.player.getModelTransformsCount())
    except jpype.JException:rejected=True
    assert rejected
    fresh();bench_before=[fixture.bench(s.player,300_000)for _ in range(5)]
    install=J('DeformedMotionCheck').class_.getDeclaredMethod('installReader');install.setAccessible(True);install.invoke(None)
    fresh();method('raiseHideArms').invoke(None,s.player);hidden_after=state();method('steadyArms').invoke(None,s.player,weapon,s.jpype.JFloat(.016));fixed=state()
    assert hidden_after['singular']and not fixed['nonfinite'],fixed
    J('aimoh.motion.PzPoseCapture').capture(s.player,s.player.getModelTransformsCount());assert state()==clean,state()
    cycles=60
    for cycle in range(cycles):
        if cycle%2==0:fixture.evaluateAnimation(s.player)
        method('raiseHideArms').invoke(None,s.player);method('steadyArms').invoke(None,s.player,weapon,s.jpype.JFloat([0.,.001,.016,.033,.08][cycle%5]))
        assert not state()['nonfinite'],(cycle,state());J('aimoh.motion.PzPoseCapture').capture(s.player,s.player.getModelTransformsCount())
    actions_after=action_samples();assert actions_before.keys()==actions_after.keys()
    action_error=max(float(np.max(np.abs(actions_before[k]-actions_after[k])))for k in actions_before);assert action_error==0,action_error
    fresh();original=np.asarray(J('aimoh.motion.PzPoseCapture').capture(s.player,s.player.getModelTransformsCount())).copy()
    raw=fixture.raw(s.player,7);raw.m00=float('nan');palette=s.player.getSkinTransforms(s.player.getSkinningData())
    assert not state()['nonfinite'];assert all(math.isfinite(getattr(m,'m'+str(r)+str(col)))for m in palette for r in range(4)for col in range(4))
    restored=np.asarray(J('aimoh.motion.PzPoseCapture').capture(s.player,s.player.getModelTransformsCount()));assert np.max(np.abs(restored-original))<2e-5
    # A valid presentation adjustment, reflection and small nonuniform scale must remain byte-identical.
    raw=fixture.raw(s.player,7);raw.m00=-.00001;raw.m11=.004;raw.m22=.05;expected=[getattr(raw,'m'+str(r)+str(col))for r in range(4)for col in range(4)]
    actual=s.player.getModelTransformAt(7);assert expected==[getattr(actual,'m'+str(r)+str(col))for r in range(4)for col in range(4)]
    # A derived writer can corrupt locals too; only this evaluation's valid base may restore them.
    fresh();Matrix=J('org.lwjgl.util.vector.Matrix4f');local_before=Matrix();s.player.getBoneTransform(7,local_before)
    poisoned=Matrix(local_before);poisoned.m00=float('nan');s.player.boneTransforms[7].set(poisoned);fixture.raw(s.player,7).m00=float('nan')
    repaired=s.player.getModelTransformAt(7);assert math.isfinite(repaired.m00)
    local_after=Matrix();s.player.getBoneTransform(7,local_after)
    assert all(abs(getattr(local_before,'m'+str(r)+str(col))-getattr(local_after,'m'+str(r)+str(col)))<2e-5 for r in range(4)for col in range(4))
    # Genuine animation zero-scale is recorded as zero; it must not become a fabricated limb.
    fresh();zero=Matrix();zero.setIdentity();zero.m00=zero.m11=zero.m22=0;s.player.boneTransforms[7].set(zero);fixture.evaluateAnimation(s.player)
    assert s.player.getModelTransformAt(7).determinant()==0
    # If the game's evaluated animation is invalid, do not hide it behind an old cached pose.
    fresh();s.player.boneTransforms[7].set(poisoned);fixture.evaluateAnimation(s.player)
    assert not math.isfinite(s.player.getModelTransformAt(7).m00)
    genuine_rejected=False
    try:J('aimoh.motion.PzPoseCapture').capture(s.player,s.player.getModelTransformsCount())
    except jpype.JException:genuine_rejected=True
    assert genuine_rejected
    fresh()
    fixture.watched(actor,False);method('raiseHideArms').invoke(None,s.player);method('steadyArms').invoke(None,s.player,weapon,s.jpype.JFloat(.016));assert state()['nonfinite'],'Unowned vanilla actor was modified'
    fresh();fixture.watched(actor,True);bench_after=[fixture.bench(s.player,300_000)for _ in range(5)]
    fixture.watched(actor,False);animation_before=[fixture.benchAnimation(s.player,3000)for _ in range(5)]
    fixture.watched(actor,True);animation_after=[fixture.benchAnimation(s.player,3000)for _ in range(5)]
    J('aimoh.pmxpreview.Main').disable(actor);fixture.raw(s.player,7).m00=float('nan');assert not math.isfinite(s.player.getModelTransformAt(7).m00),'Disable retained source-pose ownership'
    result=dict(status='PASS_ACTUAL_WRITER_AND_COMMON_GETTER_PALETTE_ADVICE',actual_modifier_sha256=sha(ads),jar_sha256=sha(jar),before=broken,after=fixed,
                zero_scale_mask_preceded_corruption=bool(hidden_before['singular']),physics_required_to_reproduce=False,real_game_animation_player=True,actual_zombiebuddy_advice=True,
                actor_identity_substituted=True,ordinary_matrix_preserved=True,unowned_actor_unchanged=True,skin_palette_rebuilt=True,
                derived_local_damage_recovered=True,genuine_animation_zero_scale_preserved=True,genuine_invalid_animation_not_suppressed=True,
                presentation_cycles=cycles,valid_action_clips=[str(p)for p in clips],valid_action_samples=len(actions_before),valid_action_max_error=action_error,disable_releases_pose_ownership=True,
                normal_getter_benchmark_ns_before=bench_before,normal_getter_benchmark_ns_after=bench_after,benchmark_calls=300000,
                animation_evaluation_ns_without_owner=animation_before,animation_evaluation_ns_with_owner=animation_after,animation_benchmark_evaluations=3000,
                game_started=False,actual_gameplay_tested=False,closed_modifier_code_copied_or_shipped=False)
    save(out/'result.json',result);print(json.dumps(result),flush=True)
finally:s.close()
