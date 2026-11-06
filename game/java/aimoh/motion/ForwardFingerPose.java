package aimoh.motion;

import com.shiroha.mmdskin.NativeFunc;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import org.joml.Quaternionf;

/** Aimoh's rotation-vector formulation: allocate bend, preserve the chain's end rotation. */
final class ForwardFingerPose {
    private record Bend(int rootOffset,int childOffset,float fraction) {}
    private final int[] destination;
    private final Bend[] bends;
    private final int inputSize;
    private final float[] frame;
    private final Quaternionf total=new Quaternionf(),rest=new Quaternionf(),change=new Quaternionf(),assigned=new Quaternionf(),remainder=new Quaternionf();
    ForwardFingerPose(int[] base,int[] parents,int[] roots,int[] tips,float[] fractions) {
        if(roots.length!=tips.length||tips.length!=fractions.length)throw new IllegalArgumentException("Finger description dimensions");
        var slots=new HashMap<Integer,Integer>();var occupied=new HashSet<Integer>();
        for(int i=0;i<base.length;i++){slots.put(base[i],i*10);occupied.add(base[i]);}
        destination=Arrays.copyOf(base,base.length+tips.length);inputSize=base.length*10;frame=new float[destination.length*10];bends=new Bend[tips.length];
        for(int i=0;i<tips.length;i++) {
            Integer at=slots.get(roots[i]);float f=fractions[i];int tip=tips[i];
            if(at==null||tip<0||tip>=parents.length||parents[tip]!=roots[i]||!occupied.add(tip)||!Float.isFinite(f)||f<=0||f>=1)throw new IllegalArgumentException("Finger description is not a two-joint chain");
            destination[base.length+i]=tip;bends[i]=new Bend(at,inputSize+i*10,f);
        }
    }
    private static void rotation(float[] pose,int at,Quaternionf q){q.set(pose[at+3],pose[at+4],-pose[at+5],-pose[at+6]).normalize();}
    private void store(int at,Quaternionf q){frame[at+3]=q.x;frame[at+4]=q.y;frame[at+5]=-q.z;frame[at+6]=-q.w;}
    void publish(NativeFunc api,long model,float[] requested,float[] reference,float strength) {
        if(requested.length!=inputSize||reference.length!=inputSize||!Float.isFinite(strength)||strength<0||strength>1)throw new IllegalArgumentException("Finger pose contract");
        System.arraycopy(requested,0,frame,0,inputSize);
        for(Bend bend:bends) {
            rotation(requested,bend.rootOffset,total);rotation(reference,bend.rootOffset,rest);
            change.set(rest).conjugate();total.mul(change,change).normalize();
            if(change.w<0)change.set(-change.x,-change.y,-change.z,-change.w);
            double sine=Math.sqrt((double)change.x*change.x+(double)change.y*change.y+(double)change.z*change.z);
            if(sine<1e-8)assigned.set(rest);
            else assigned.rotationAxis((float)(2*Math.atan2(sine,change.w)*bend.fraction),(float)(change.x/sine),(float)(change.y/sine),(float)(change.z/sine)).mul(rest);
            total.slerp(assigned,strength,assigned).normalize();assigned.difference(total,remainder).normalize();
            store(bend.rootOffset,assigned);int at=bend.childOffset;frame[at]=frame[at+1]=frame[at+2]=0;store(at,remainder);frame[at+7]=frame[at+8]=frame[at+9]=1;
        }
        if(api.SetBoneAdditiveTransformBatch(model,destination,frame)!=destination.length)throw new IllegalStateException("External finger pose was not accepted atomically");
    }
}
