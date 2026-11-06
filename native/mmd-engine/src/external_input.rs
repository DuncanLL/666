//! Aimoh external-pose boundary. Written against upstream types and our profile contract.
//! The legacy JNI names are retained only as a consumer ABI; no VMD policy is imported.
use glam::{Mat4, Quat, Vec3};
use jni::{JNIEnv, objects::{JClass, JIntArray, JFloatArray, JLongArray, JBooleanArray}, sys::{jlong,jint,jboolean,jstring}};
use crate::{model::MmdModel, jni_bridge::MODELS};

#[derive(Default)]
pub struct ExternalInput {
    pub pose: Vec<(usize, Vec3, Quat, Vec3)>,
    pub morphs: Vec<(usize,f32)>,
    pub foot_ik_names: Vec<String>,
    pub game_fk: bool,
    pub physics_reset: bool,
    parent_order:Vec<usize>,
    simulated:Vec<Option<(Mat4,bool)>>,
    motion_sample:Option<(Vec3,Vec3)>,
    rebase_distance_sq:f32,
}
impl ExternalInput {
    pub fn sync_motion(&mut self,physics:&crate::physics::MMDPhysics,bones:&[Mat4],world:Mat4,dt:f32,config:&crate::physics::PhysicsConfig) {
        physics.sync_bodies(bones);
        let origin=world.w_axis.truncate();
        let velocity=self.motion_sample.map(|(previous,_)|(origin-previous)/dt).unwrap_or(Vec3::ZERO);
        if let Some((_,previous_velocity))=self.motion_sample {
            // Inertia comes from a change in velocity, not a force repeated at constant speed.
            let change=velocity-previous_velocity;
            let local=world.inverse().transform_vector3(change)*config.inertia_strength;
            let impulse_velocity=Vec3::new(-local.x,-local.y,local.z).clamp_length_max(config.max_linear_velocity);
            for rigid in &physics.rigid_bodies {
                if rigid.physics_mode==crate::physics::PhysicsMode::FollowBone {continue;}
                if let Some(body)=&rigid.bullet_body {
                    let v=body.get_linear_velocity()+impulse_velocity;
                    body.set_linear_velocity(v.x,v.y,v.z);
                }
            }
        }
        self.motion_sample=Some((origin,velocity));
    }
    pub fn clear_motion(&mut self){self.motion_sample=None;}
    pub fn motion_is_discontinuous(&self,world:Mat4)->bool {
        self.motion_sample.map(|(previous,_)|previous.distance_squared(world.w_axis.truncate())>self.rebase_distance_sq).unwrap_or(false)
    }
    pub fn merge_physics(&mut self,bones:&mut crate::skeleton::BoneSet,physics:&crate::physics::MMDPhysics) {
        use crate::physics::{PhysicsMode,inv_z};
        let count=bones.bone_count();
        if self.parent_order.len()!=count {
            let mut ranked=Vec::with_capacity(count);
            for id in 0..count {
                let mut parent=bones.get_bone(id).unwrap().parent_index;let mut depth=0;
                while parent>=0 && (parent as usize)<count && depth<count {
                    depth+=1;parent=bones.get_bone(parent as usize).unwrap().parent_index;
                }
                ranked.push((depth,id));
            }
            ranked.sort_unstable();self.parent_order=ranked.into_iter().map(|(_,id)|id).collect();
            self.simulated.resize(count,None);
        }
        self.simulated.fill(None);
        for rb in &physics.rigid_bodies {
            if rb.bone_index<0 || rb.bone_index as usize>=count || rb.physics_mode==PhysicsMode::FollowBone {continue;}
            if let Some(body)=&rb.bullet_body {
                self.simulated[rb.bone_index as usize]=Some((inv_z(body.get_transform()*rb.body_offset_matrix_inverse),rb.physics_mode==PhysicsMode::PhysicsWithBone));
            }
        }
        // Commit a hierarchy, not an unordered list of independently translated joints.
        for &id in &self.parent_order {
            let bone=bones.get_bone(id).unwrap();let parent=bone.parent_index;let local=bone.local_to_parent;
            let inherited=if parent>=0 {(bones.get_bone(parent as usize).unwrap().local_to_world)*local}else{local};
            if let Some((mut pose,position_from_parent))=self.simulated[id] {
                if position_from_parent {pose.w_axis=inherited.w_axis;}
                bones.set_global_transform_physics(id,pose);
            }else{bones.get_bone_mut(id).unwrap().local_to_world=inherited;}
        }
    }
    pub fn apply(&self, model: &mut MmdModel) {
        for &(index, translation, rotation, scale) in &self.pose {
            if let Some(bone)=model.bone_manager.get_bone_mut(index) {
                // A complete local pose owns this joint. Helpers retain authored append/IK.
                bone.animation_translate=translation;
                bone.animation_rotate=rotation;
                bone.external_local=Some(Mat4::from_scale_rotation_translation(scale,rotation,bone.body_shift+translation));
            }
        }
        for &(index,weight) in &self.morphs {model.morph_manager.set_morph_weight(index,weight);}
        if self.game_fk {for name in &self.foot_ik_names {model.bone_manager.set_ik_enabled_by_name(name,false);}}
    }
}

#[no_mangle]
pub extern "system" fn Java_com_shiroha_mmdskin_NativeFunc_GetBoneMetadata(env:JNIEnv,_:JClass,handle:jlong)->jstring {
    let models=MODELS.read().unwrap_or_else(|p|p.into_inner());
    let Some(shared)=models.get(&handle) else{return std::ptr::null_mut()};
    let model=shared.lock().unwrap_or_else(|p|p.into_inner());
    let mut rows=Vec::with_capacity(model.bone_manager.bone_count());
    for index in 0..model.bone_manager.bone_count() {
        if let Some(b)=model.bone_manager.get_bone(index) {
            rows.push(serde_json::json!({"index":index,"name":b.name,"parent":b.parent_index,"initial_position":b.initial_position.to_array(),"flags":b.flags.bits()}));
        }
    }
    env.new_string(serde_json::to_string(&rows).unwrap()).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_shiroha_mmdskin_NativeFunc_SetBoneAdditiveTransformBatch(mut env:JNIEnv,_:JClass,handle:jlong,indices:JIntArray,values:JFloatArray)->jint {
    let Ok(count)=env.get_array_length(&indices) else{return 0};
    let Ok(length)=env.get_array_length(&values) else{return 0};
    if count<0 || count>65536 || length!=count*10 {let _=env.throw_new("java/lang/IllegalArgumentException","Invalid external pose dimensions");return 0;}
    let mut ids=vec![0;count as usize];let mut input=vec![0.;length as usize];
    if env.get_int_array_region(&indices,0,&mut ids).is_err()||env.get_float_array_region(&values,0,&mut input).is_err(){return 0;}
    let models=MODELS.read().unwrap_or_else(|p|p.into_inner());let Some(shared)=models.get(&handle)else{return 0};
    let mut model=shared.lock().unwrap_or_else(|p|p.into_inner());let mut pose=Vec::with_capacity(ids.len());
    if model.external_input.rebase_distance_sq==0.0 {
        let mut low=f32::INFINITY;let mut high=f32::NEG_INFINITY;
        for index in 0..model.bone_manager.bone_count(){let y=model.bone_manager.get_bone(index).unwrap().initial_position.y;low=low.min(y);high=high.max(y);}
        model.external_input.rebase_distance_sq=((high-low).max(1.0)*2.0).powi(2);
    }
    for (index,qps)in ids.iter().zip(input.chunks_exact(10)) {
        if *index<0||*index as usize>=model.bone_manager.bone_count()||qps.iter().any(|v|!v.is_finite()) {
            let _=env.throw_new("java/lang/IllegalArgumentException","Invalid external pose value");return 0;
        }
        let q=Quat::from_xyzw(qps[3],qps[4],-qps[5],-qps[6]);
        if q.length_squared()<1e-12 {let _=env.throw_new("java/lang/IllegalArgumentException","Zero external rotation");return 0;}
        pose.push((*index as usize,Vec3::new(qps[0],qps[1],-qps[2]),q.normalize(),Vec3::from_slice(&qps[7..10])));
    }
    model.external_input.pose=pose;count
}

#[no_mangle]
pub extern "system" fn Java_com_shiroha_mmdskin_NativeFunc_SetPZLegIKSuppressed(_env:JNIEnv,_:JClass,handle:jlong,suppressed:jboolean) {
    let models=MODELS.read().unwrap_or_else(|p|p.into_inner());let Some(shared)=models.get(&handle)else{return};
    let mut model=shared.lock().unwrap_or_else(|p|p.into_inner());
    if model.external_input.foot_ik_names.is_empty() {
        let mut names=Vec::new();
        for i in 0..model.bone_manager.bone_count() {
            let Some(b)=model.bone_manager.get_bone(i)else{continue};let name=b.name.replace('Ｉ',"I").replace('Ｋ',"K");
            if b.is_ik()&&(name.ends_with("足IK")||name.ends_with("つま先IK")){names.push(b.name.clone());}
        }
        model.external_input.foot_ik_names=names;
    }
    model.external_input.game_fk=suppressed!=0;
}

#[no_mangle]
pub extern "system" fn Java_com_shiroha_mmdskin_NativeFunc_SetPZAnimationState(mut env:JNIEnv,_:JClass,handle:jlong,animations:JLongArray,frames:JFloatArray,additive:JBooleanArray,indices:JIntArray,weights:JFloatArray) {
    // Existing Aimoh consumers submit only an explicit manual Morph baseline.
    if env.get_array_length(&animations).unwrap_or(-1)!=0||env.get_array_length(&frames).unwrap_or(-1)!=0||env.get_array_length(&additive).unwrap_or(-1)!=0 {
        let _=env.throw_new("java/lang/IllegalArgumentException","Native animation playback is not part of the Aimoh pose input");return;
    }
    let Ok(count)=env.get_array_length(&indices)else{return};if count<0||count>65536||env.get_array_length(&weights).unwrap_or(-1)!=count {let _=env.throw_new("java/lang/IllegalArgumentException","Invalid Morph baseline dimensions");return;}
    let mut ids=vec![0;count as usize];let mut values=vec![0.;count as usize];
    if env.get_int_array_region(&indices,0,&mut ids).is_err()||env.get_float_array_region(&weights,0,&mut values).is_err(){return;}
    let models=MODELS.read().unwrap_or_else(|p|p.into_inner());let Some(shared)=models.get(&handle)else{return};let mut model=shared.lock().unwrap_or_else(|p|p.into_inner());
    let mut baseline=Vec::with_capacity(ids.len());
    for (id,value)in ids.into_iter().zip(values) {
        if id<0||id as usize>=model.morph_manager.morph_count()||!value.is_finite()||!(0.0..=1.0).contains(&value) {let _=env.throw_new("java/lang/IllegalArgumentException","Invalid Morph baseline value");return;}
        baseline.push((id as usize,value));
    }
    let previous=std::mem::take(&mut model.external_input.morphs);
    for (id,_)in previous{model.morph_manager.set_morph_weight(id,0.0);}
    model.external_input.morphs=baseline;
}

#[no_mangle]
pub extern "system" fn Java_com_shiroha_mmdskin_NativeFunc_ClearBoneAdditiveTransforms(_env:JNIEnv,_:JClass,handle:jlong) {
    let models=MODELS.read().unwrap_or_else(|p|p.into_inner());
    if let Some(shared)=models.get(&handle){shared.lock().unwrap_or_else(|p|p.into_inner()).external_input.pose.clear();}
}

#[no_mangle]
pub extern "system" fn Java_com_shiroha_mmdskin_NativeFunc_GetRuntimeStats(env:JNIEnv,_:JClass)->jstring {
    let stats=crate::physics::MMDPhysics::alloc_stats();
    let models=MODELS.read().unwrap_or_else(|p|p.into_inner()).len();
    let value=serde_json::json!({"models":models,"worlds":stats.worlds,"shapes":stats.shapes,"bodies":stats.rigid_bodies,"constraints":stats.constraints,"motion_states":stats.motion_states});
    env.new_string(value.to_string()).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}
