package aimoh.pmxpreview;

import aimoh.motion.PmxModel;
import aimoh.motion.WorldPoseRetargeter;
import com.shiroha.mmdskin.NativeFunc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import zombie.characters.IsoGameCharacter;
import zombie.characters.action.ActionContext;

/** Common empty-hand preview, using the existing four-finger FK transport. */
final class IdleHandPose {
    private static final MethodHandle CHILD_STATE_COUNT = childStateCountHandle();
    // Bob_Walk at 25%, expressed relative to each source hand; same sample as the workbench.
    private static final float[][] REFERENCE={{-.00034764406f,.49012056f,.00019487736f,-.87165463f},
        {-.00035598874f,-.45357579f,.00018033385f,.89121765f}};
    private final WorldPoseRetargeter core;
    private final int[] hands=new int[2],fingers=new int[2];
    private final Quaternionf[] reference=new Quaternionf[2];
    private final Quaternionf hand=new Quaternionf(),finger=new Quaternionf(),local=new Quaternionf();
    private final Matrix4f matrix=new Matrix4f(),rotation=new Matrix4f();
    private final Vector3f scale=new Vector3f();
    private final float[] output;
    private final boolean available;
    private float strength,weight;

    IdleHandPose(NativeFunc api,PmxModel model,JSONObject profile) {
        core=model.retargeter();
        JSONArray names=profile.getJSONArray("source_bone_names"),parents=profile.getJSONArray("parents"),pos=profile.getJSONArray("initial_positions");
        for(int side=0;side<2;side++) {
            String prefix=side==0?"Bip01_L_":"Bip01_R_";
            hands[side]=find(names,prefix+"Hand");fingers[side]=find(names,prefix+"Finger1");
            float[] q=REFERENCE[side];reference[side]=new Quaternionf(q[0],q[1],q[2],q[3]).normalize();
        }
        JSONArray metadata=new JSONArray(api.GetBoneMetadata(model.handle()));
        HashMap<String,Integer> lookup=new HashMap<>();
        for(int i=0;i<metadata.length();i++)lookup.put(normalize(metadata.getJSONObject(i).getString("name")),i);
        ArrayList<Integer> roots=new ArrayList<>(),children=new ArrayList<>();ArrayList<Float> shares=new ArrayList<>();
        HashSet<Integer> seen=new HashSet<>();JSONArray mapping=profile.getJSONArray("mapping");
        for(int i=0;i<mapping.length();i++) {
            JSONObject entry=mapping.getJSONObject(i);
            if(!entry.getString("source").endsWith("_Finger1"))continue;
            int root=entry.getInt("target_index");if(root<0||root>=metadata.length())continue;
            String name=normalize(metadata.getJSONObject(root).getString("name"));if(!name.endsWith("1"))continue;
            Integer child=lookup.get(name.substring(0,name.length()-1)+"2"),end=lookup.get(name.substring(0,name.length()-1)+"3");
            if(child==null||end==null||parents.getInt(child)!=root||parents.getInt(end)!=child||!seen.add(child))continue;
            float a=distance(pos,root,child),b=distance(pos,child,end);
            if(!Float.isFinite(a+b)||a<=1e-5f||b<=1e-5f)continue;
            roots.add(root);children.add(child);shares.add(a/(a+b));
        }
        int[] r=new int[roots.size()],c=new int[roots.size()];float[] f=new float[roots.size()];
        for(int i=0;i<r.length;i++){r[i]=roots.get(i);c[i]=children.get(i);f[i]=shares.get(i);}
        core.configureFingerPreview(r,c,f);
        available=r.length>0&&hands[0]>=0&&hands[1]>=0&&fingers[0]>=0&&fingers[1]>=0;
        output=new float[names.length()*16];setStrength(profile.optDouble("hand_relax_strength",.7));
    }
    private static int find(JSONArray names,String name) {
        for(int i=0;i<names.length();i++)if(name.equals(names.getString(i)))return i;
        return -1;
    }
    private static String normalize(String s){return Normalizer.normalize(s,Normalizer.Form.NFKC);}
    private static float distance(JSONArray p,int a,int b) {
        float x=(float)(p.getDouble(a*3)-p.getDouble(b*3)),y=(float)(p.getDouble(a*3+1)-p.getDouble(b*3+1)),z=(float)(p.getDouble(a*3+2)-p.getDouble(b*3+2));
        return (float)Math.sqrt(x*x+y*y+z*z);
    }
    void setStrength(double value) { strength=Double.isFinite(value)&&value>=0&&value<=1?(float)value:0; }
    private static MethodHandle childStateCountHandle() {
        var lookup = MethodHandles.publicLookup();
        var countType = MethodType.methodType(int.class);
        try {
            try {
                return lookup.findVirtual(ActionContext.class, "childStateCount", countType);
            } catch (NoSuchMethodException oldBuild) {
                // B42.21 exposes the list; B42.22 exposes the count.
                var children = lookup.findVirtual(ActionContext.class, "getChildStates", MethodType.methodType(List.class));
                return MethodHandles.filterReturnValue(children, lookup.findVirtual(List.class, "size", countType));
            }
        } catch (ReflectiveOperationException unsupportedBuild) {
            throw new ExceptionInInitializerError(unsupportedBuild);
        }
    }
    private static int childStateCount(ActionContext action) {
        try {
            return (int) CHILD_STATE_COUNT.invokeExact(action);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }
    static boolean eligible(IsoGameCharacter actor,boolean holding) {
        if(holding||actor==null)return false;
        try {
            var action=actor.getActionContext();if(action==null)return false;
            return "idle".equalsIgnoreCase(action.getCurrentStateName())&&childStateCount(action)==0
                &&!actor.isMoving()&&!actor.isSneaking()&&!actor.isAiming()&&!actor.isSitting()&&!actor.isClimbing()
                &&!actor.isPerformingAttackAnimation()&&!actor.getVariableBoolean("IsPerformingAnAction")
                &&!actor.getVariableBoolean("initiateAttack");
        } catch(IllegalStateException missingActorComponents) { return false; }
    }
    float[] apply(float[] source,int[] sourceIndices,float seconds,boolean eligible) {
        boolean valid=available;
        if(valid)for(int side=0;side<2;side++)valid&=sourceIndices[hands[side]]>=0&&sourceIndices[fingers[side]]>=0;
        float target=eligible&&valid?strength:0,previous=weight;
        if(seconds>0&&weight!=target)weight+=(target-weight)*(1f-(float)Math.exp(-seconds/.12));
        if(Math.abs(weight-target)<1e-4f)weight=target;
        if(weight!=previous)core.setFingerPreviewWeight(weight);
        if(weight<=0)return source;
        System.arraycopy(source,0,output,0,source.length);
        for(int side=0;side<2;side++) {
            matrix.set(source,hands[side]*16).getUnnormalizedRotation(hand).normalize();
            int at=fingers[side]*16;matrix.set(source,at);matrix.getUnnormalizedRotation(finger).normalize();
            local.set(hand).invert().mul(finger).normalize().slerp(reference[side],weight);
            finger.set(hand).mul(local).normalize();matrix.getScale(scale);rotation.rotation(finger);
            matrix.m00(rotation.m00()*scale.x).m01(rotation.m01()*scale.x).m02(rotation.m02()*scale.x)
                .m10(rotation.m10()*scale.y).m11(rotation.m11()*scale.y).m12(rotation.m12()*scale.y)
                .m20(rotation.m20()*scale.z).m21(rotation.m21()*scale.z).m22(rotation.m22()*scale.z);
            matrix.get(output,at);
        }
        return output;
    }
}
