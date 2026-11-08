package aimoh.pmxpreview;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import org.joml.Matrix4f;
import viewpoint.core.Frame;
import viewpoint.render.ModelDraws;
import zombie.characters.IsoGameCharacter;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.ModelMesh;
import zombie.core.textures.Texture;

/** Bounded adapter to the inspected Viewpoint 0.1.6.1 draw layout. */
final class DrawAccess {
    private static final Field SIM_NANOS=simulationClock();
    private static Field simulationClock(){try{return Frame.class.getField("simNanos");}catch(NoSuchFieldException legacy){return null;}}
    static long frameNanos(Frame frame){
        if(SIM_NANOS==null)return System.nanoTime();
        try{return SIM_NANOS.getLong(frame);}catch(IllegalAccessException e){throw new IllegalStateException(e);}
    }
    private static final Field PALETTES=field(ModelDraws.class,"palettes"),MESHES=field(ModelDraws.class,"meshes"),INSTANCES=field(ModelDraws.class,"instances"),VALUES=field(ModelDraws.class,"values"),
        FLAGS=field(ModelDraws.class,"flags"),TEXTURES=field(ModelDraws.class,"textures");
    private static final Field POSE_BONES=optionalField("poseBones");
    private static final Field[] MOTION_FIELDS={optionalField("previous"),optionalField("shadowPrevious"),POSE_BONES,optionalField("lastDraw")};
    private static final Field RIDERS=optionalField("riders"),RIDER_ON=RIDERS==null?null:field(RIDERS.getType(),"on"),RIDER_BONES=RIDERS==null?null:field(RIDERS.getType(),"bones");
    private static Field optionalField(String name) {
        try {Field f=ModelDraws.class.getDeclaredField(name);f.setAccessible(true);return f;}
        catch(NoSuchFieldException e){return null;}
    }
    private static Field field(Class<?> type,String name) {
        try { Field f=type.getDeclaredField(name);f.setAccessible(true);return f; }
        catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    private static Object get(Field f,Object object) {
        try { return f.get(object); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
    static ModelInstance instance(ModelDraws draws,int n) { return ((ModelInstance[])get(INSTANCES,draws))[n]; }
    static ModelMesh mesh(ModelDraws draws,int n) { return ((ModelMesh[])get(MESHES,draws))[n]; }
    static float[] values(ModelDraws draws) { return (float[])get(VALUES,draws); }
    static int poseBones(ModelDraws draws,int n){return POSE_BONES==null?0:((int[])get(POSE_BONES,draws))[n];}
    static void saveMotion(ModelDraws draws,int n,int[] output,int at) {
        for(int i=0;i<MOTION_FIELDS.length;i++)output[at+i]=MOTION_FIELDS[i]==null?-1:((int[])get(MOTION_FIELDS[i],draws))[n];
        if(RIDERS!=null){Object riders=get(RIDERS,draws);output[at+4]=((int[])get(RIDER_ON,riders))[n];output[at+5]=((int[])get(RIDER_BONES,riders))[n];}
    }
    static void restoreMotion(ModelDraws draws,int n,int[] input,int at) {
        for(int i=0;i<MOTION_FIELDS.length;i++)if(MOTION_FIELDS[i]!=null)((int[])get(MOTION_FIELDS[i],draws))[n]=input[at+i];
        if(RIDERS!=null){Object riders=get(RIDERS,draws);((int[])get(RIDER_ON,riders))[n]=input[at+4];((int[])get(RIDER_BONES,riders))[n]=input[at+5];}
    }
    static void ownPlacement(ModelDraws draws,int n,int parent,boolean continuous) {
        // Carry the rigid item with its PMX body, never with the hidden vanilla hand bone.
        if(RIDERS!=null){Object riders=get(RIDERS,draws);((int[])get(RIDER_ON,riders))[n]=parent;((int[])get(RIDER_BONES,riders))[n]=-1;}
        if(!continuous)flags(draws,n,draws.flags(n)&~ModelDraws.MOTION);
    }
    static void header(ModelDraws draws,int n,float[] header) {
        int palette=(int)values(draws)[n*68+52];float[] values=(float[])get(PALETTES,draws);
        if(palette<0||palette*12+header.length>values.length)throw new IllegalArgumentException("PMX presentation palette differs");
        System.arraycopy(header,0,values,palette*12,header.length);
    }
    static void texture(ModelDraws draws,int n,Texture texture){((Texture[])get(TEXTURES,draws))[n]=texture;}
    static Matrix4f matrix(ModelDraws draws,int n) { return new Matrix4f().set(values(draws),n*68); }
    static void matrix(ModelDraws draws,int n,Matrix4f m) { m.get(values(draws),n*68); }
    static void flags(ModelDraws draws,int n,int flags) {
        ((int[])get(FLAGS,draws))[n]=flags;values(draws)[n*68+55]=flags;
    }
    static void style(ModelDraws draws,int from,int to) {
        float[] a=values(draws);System.arraycopy(a,from*68+20,a,to*68+20,4);
        System.arraycopy(a,from*68+32,a,to*68+32,4);System.arraycopy(a,from*68+60,a,to*68+60,4);
        flags(draws,to,draws.flags(to)|(draws.flags(from)&(ModelDraws.OUTLINE_BEHIND|ModelDraws.TARGET)));
    }
    static final class Gear {
        final ModelInstance root;final int side;final String key;
        Gear(ModelInstance root,int side,String key) { this.root=root;this.side=side;this.key=key; }
    }
    private static void collect(ModelInstance root,Gear gear,IdentityHashMap<ModelInstance,Gear> output) {
        if (root==null || output.put(root,gear)!=null) return;
        for (ModelInstance child:root.sub) collect(child,gear,output);
    }
    static IdentityHashMap<ModelInstance,Gear> equipment(IsoGameCharacter actor,ModelManager.ModelSlot slot,ModelDraws draws,int first,int end) {
        IdentityHashMap<ModelInstance,Gear> output=new IdentityHashMap<>();
        collect(actor.primaryHandModel,new Gear(actor.primaryHandModel,1,actor.getPrimaryHandItem()==null?"":actor.getPrimaryHandItem().getType()),output);
        collect(actor.secondaryHandModel,new Gear(actor.secondaryHandModel,0,actor.getSecondaryHandItem()==null?"":actor.getSecondaryHandItem().getType()),output);
        // A costume replaces every worn/attached visual; only actual hand-held trees remain.
        return output;
    }
}
