package aimoh.motion;

import com.shiroha.mmdskin.NativeFunc;
import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.json.JSONObject;
import static java.lang.foreign.ValueLayout.*;

/** Local chest volume deformation, evaluated once after the final pose. */
public final class ChestSoftBody implements AutoCloseable {
    private static Library library;
    private static final class Library {
        final Arena arena=Arena.ofShared();
        final SymbolLookup symbols;
        Library(Path path){symbols=SymbolLookup.libraryLookup(path,arena);}
        MethodHandle call(String name,FunctionDescriptor type){return Linker.nativeLinker().downcallHandle(symbols.findOrThrow(name),type);}
    }
    public static synchronized void initialize(Path path){
        if(library==null)library=new Library(path);
    }
    private static final String[] KEYS={"softness","damping","inertia","strength","lateral_limit","vertical_limit",
        "depth_limit","cushion","bone_follow","lateral","landing","strain"};
    private static final double[] DEFAULT={100*(.75-.45)/.67,100*(.045-.035)/.085,50,100,85,105,90,30,0,100,100,100};
    private static final int[] MIN={0,0,0,0,60,60,60,0,0,0,0,0};
    private static final int[] MAX={100,100,100,100,140,140,140,60,100,250,200,200};
    private final Arena arena=Arena.ofShared();
    private final NativeFunc api;
    private final long model;
    private final float scale;
    private final Region[] regions;
    private final int[] affected;
    private final ByteBuffer palette;
    private final MemorySegment frames,quality;
    private MemorySegment handle;
    private final MethodHandle delete,add,setFrames,step,delta,tune,getQuality,create,destroy,signatureSetter,strainSetter,impulse;
    private MemorySegment solver=MemorySegment.NULL;
    private final MemorySegment impulseVector;
    private final JoltChestMotion motion=new JoltChestMotion();
    private float appliedSignature=-1, appliedStrain;
    private float sideX, sideZ;
    private int[] gpuSpans,gpuRows;
    private final double[] values=DEFAULT.clone();
    private boolean reset=true,enabled=true,failed,closed;
    private String error="";
    private static final class Region {
        int bone,count;int[] ids;float[] weights,swingWeights;
        MemorySegment positions,normals;
        final float[] center=new float[3], pivot=new float[3], movingPivot=new float[3];
        float angle;
    }

    public static ChestSoftBody load(NativeFunc api,long model,JSONObject profile)throws IOException {
        return load(api,model,profile,null);
    }
    static ChestSoftBody load(NativeFunc api,long model,JSONObject profile,Path sidecar)throws IOException {
        String sha=profile.getString("pmx_sha256");
        if(!sha.matches("[0-9a-f]{64}"))throw new IOException("Invalid PMX identity");
        byte[] data;
        if(sidecar!=null&&Files.isRegularFile(sidecar))data=Files.readAllBytes(sidecar);
        else try(var input=ChestSoftBody.class.getResourceAsStream("/aimoh/pmxpreview/chest/"+sha+".bin")){
            if(input==null)return null;data=input.readAllBytes();
        }
        if(library==null)throw new IllegalStateException("Chest native component was not initialized");
        return new ChestSoftBody(api,model,profile,data);
    }
    private ChestSoftBody(NativeFunc api,long model,JSONObject profile,byte[] data)throws IOException {
        this.api=api;this.model=model;scale=1/profile.getFloat("unit_scale");
        Library lib=library;
        signatureSetter=lib.call("sb_set_signature",FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS,JAVA_INT,JAVA_FLOAT));
        strainSetter=lib.call("jolt_set_strain_drive",FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_FLOAT));
        impulse=lib.call("sb_impulse",FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_INT,ADDRESS));
        create=lib.call("backend_create",FunctionDescriptor.of(ADDRESS,ADDRESS,JAVA_INT));
        destroy=lib.call("backend_destroy",FunctionDescriptor.ofVoid(ADDRESS));
        delete=lib.call("sb_delete",FunctionDescriptor.ofVoid(ADDRESS));
        add=lib.call("sb_add",FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS,ADDRESS,ADDRESS,ADDRESS,ADDRESS,JAVA_INT,JAVA_INT,JAVA_FLOAT,JAVA_FLOAT,JAVA_FLOAT,JAVA_FLOAT));
        setFrames=lib.call("sb_frames",FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS,JAVA_INT));
        step=lib.call("backend_step",FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_FLOAT,JAVA_FLOAT,JAVA_FLOAT,JAVA_FLOAT,JAVA_FLOAT,JAVA_FLOAT));
        delta=lib.call("sb_delta",FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_INT,ADDRESS,ADDRESS));
        tune=lib.call("sb_tune",FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_FLOAT,JAVA_FLOAT,JAVA_FLOAT));
        getQuality=lib.call("sb_quality",FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_INT,ADDRESS));
        ByteBuffer input=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic=new byte[8],sha=new byte[32];input.get(magic);input.get(sha);
        if(!Arrays.equals(magic,"ACSB0001".getBytes(java.nio.charset.StandardCharsets.US_ASCII))||
            !HexFormat.of().formatHex(sha).equals(profile.getString("pmx_sha256")))throw new IOException("Chest data identity mismatch");
        int bones=input.getInt(),vertices=input.getInt(),count=input.getInt();
        if(bones!=profile.getInt("bones")||vertices!=profile.getInt("vertices")||count<1||count>16)throw new IOException("Chest data structure mismatch");
        regions=new Region[count];frames=arena.allocate(JAVA_FLOAT,count*16);quality=arena.allocate(JAVA_DOUBLE,2);
        impulseVector=arena.allocate(JAVA_FLOAT,3);
        palette=arena.allocate(JAVA_FLOAT,bones*16L).asByteBuffer().order(ByteOrder.nativeOrder());
        BitSet used=new BitSet(vertices);int[] driven=new int[count];
        try {
            handle=(MemorySegment)lib.call("sb_new",FunctionDescriptor.of(ADDRESS)).invokeExact();
            if(handle.address()==0)throw new IOException("Chest world allocation failed");
            for(int r=0;r<count;r++) {
                Region region=new Region();regions[r]=region;region.bone=input.getInt();region.count=input.getInt();float mass=input.getFloat();
                if(region.bone<0||region.bone>=bones||region.count<1||region.count>vertices||!Float.isFinite(mass)||mass<=0)throw new IOException("Invalid chest region");
                MemorySegment center=read(input,3),radius=read(input,3),back=read(input,3);
                region.ids=new int[region.count];region.weights=new float[region.count];
                for(int i=0;i<region.count;i++){
                    int id=input.getInt();if(id<0||id>=vertices||(i>0&&id<=region.ids[i-1]))throw new IOException("Invalid chest vertex");
                    region.ids[i]=id;used.set(id);
                }
                for(int i=0;i<region.count;i++){
                    float w=input.getFloat();if(!Float.isFinite(w)||w<=0||w>1.00001f)throw new IOException("Invalid chest weight");region.weights[i]=w;
                }
                MemorySegment rest=read(input,region.count*3),normals=read(input,region.count*3);
                prepareSwing(region,center,radius,back,rest);
                int result=(int)add.invokeExact(handle,center,radius,back,rest,normals,region.count,40,mass,.45f,.045f,0f);
                if(result!=r)throw new IOException("Chest region allocation failed: "+result);
                region.positions=arena.allocate(JAVA_FLOAT,region.count*3L);region.normals=arena.allocate(JAVA_FLOAT,region.count*3L);driven[r]=region.bone;
            }
            if(input.hasRemaining())throw new IOException("Unexpected chest data tail");
            affected=used.stream().toArray();
            if(!api.SetAnimationOwnedPhysicsBones(model,driven))throw new IOException("Cannot transfer chest bone ownership");
        }catch(Throwable e){close();if(e instanceof IOException exception)throw exception;throw new IOException("Cannot initialize chest physics",e);}
    }
    private MemorySegment read(ByteBuffer input,int count)throws IOException {
        MemorySegment result=arena.allocate(JAVA_FLOAT,count);
        for(int i=0;i<count;i++){float value=input.getFloat();if(!Float.isFinite(value))throw new IOException("Non-finite chest data");result.setAtIndex(JAVA_FLOAT,i,value);}
        return result;
    }
    public boolean available(){return !closed;}
    public String error(){return error;}
    private static void prepareSwing(Region region,MemorySegment center,MemorySegment radius,MemorySegment back,MemorySegment rest) {
        float reachInverse=0;
        for(int k=0;k<3;k++){
            region.center[k]=center.getAtIndex(JAVA_FLOAT,k);
            float normalized=back.getAtIndex(JAVA_FLOAT,k)/radius.getAtIndex(JAVA_FLOAT,k);
            reachInverse+=normalized*normalized;
        }
        reachInverse=(float)Math.sqrt(reachInverse);
        for(int k=0;k<3;k++)region.pivot[k]=region.center[k]+back.getAtIndex(JAVA_FLOAT,k)*.95f/reachInverse;
        region.swingWeights=new float[region.count];
        for(int i=0;i<region.count;i++){
            float rear=0;
            for(int k=0;k<3;k++)rear+=(rest.getAtIndex(JAVA_FLOAT,i*3L+k)-region.center[k])*back.getAtIndex(JAVA_FLOAT,k);
            float skin=Math.clamp((region.weights[i]-.35f)/.30f,0,1);
            float edge=Math.clamp((.86f-rear*reachInverse)/.68f,0,1);
            region.swingWeights[i]=skin*skin*(3-2*skin)*edge*edge*(3-2*edge);
        }
    }
    public double slider(int index){
        return index>=0&&index<values.length?values[index]:-1;
    }
    public JSONObject settings(){
        JSONObject result=new JSONObject();
        for(int i=0;i<KEYS.length;i++)result.put(KEYS[i],values[i]);
        return result;
    }
    public void settings(JSONObject input){applySettings(prepareSettings(input));}
    double[] prepareSettings(JSONObject input){
        double[] next=values.clone();
        for(String key:input.keySet())if(!Arrays.asList(KEYS).contains(key))throw new IllegalArgumentException("Unknown chest setting: "+key);
        for(int i=0;i<KEYS.length;i++){
            if(input.has(KEYS[i]))next[i]=input.getDouble(KEYS[i]);
            if(!Double.isFinite(next[i])||next[i]<MIN[i]||next[i]>MAX[i])throw new IllegalArgumentException("Invalid chest slider: "+KEYS[i]);
        }
        return next;
    }
    void applySettings(double[] next){
        try{
            if(next[0]!=values[0]||next[1]!=values[1]||next[2]!=values[2]){
                int result=(int)tune.invokeExact(handle,stiffness(next[0]),(float)(.035+.085*next[1]/100),(float)(.6+.8*next[2]/100));
                if(result!=0)throw new IllegalStateException("Chest parameter update failed");
            }
            if(values[3]==0&&next[3]>0)reset=true;
            System.arraycopy(next,0,values,0,values.length);
        }catch(Throwable e){throw new IllegalStateException("Chest parameter update failed",e);}
    }
    private static float stiffness(double softness){return (float)Math.clamp(.75-.67*softness/100,.08,.75);}
    public void slider(int index,double value){
        if(index<0||index>=KEYS.length)throw new IllegalArgumentException("Invalid chest slider");
        settings(new JSONObject().put(KEYS[index],value));
    }
    public void defaults(){applySettings(DEFAULT);}
    public void enabled(boolean value){if(enabled!=value){enabled=value;reset=true;}}
    public void reset(){reset=true;failed=false;error="";}
    public void evaluate(float seconds) {
        if(!enabled||failed||closed||values[3]==0)return;
        try{
            palette.clear();if(api.CopySkinningMatricesToBuffer(model,palette)!=palette.capacity()/64)throw new IllegalStateException("Incomplete chest palette");
            for(int r=0;r<regions.length;r++)for(int i=0;i<16;i++){
                float value=palette.getFloat(regions[r].bone*64+i*4);if(i>=12&&i<15)value*=scale;
                frames.setAtIndex(JAVA_FLOAT,r*16L+i,value);
            }
            if((int)setFrames.invokeExact(handle,frames,reset?1:0)!=0)throw new IllegalStateException("Invalid chest pose");
            if(solver.address()==0){
                solver=(MemorySegment)create.invokeExact(handle,2);
                if(solver.address()==0)throw new IllegalStateException("Jolt chest backend initialization failed");
            }
            float elapsed=Math.clamp(seconds,0f,.08f);
            evaluateInertia(seconds);
            reset=false;
            float signature=(float)values[8];
            if(signature!=appliedSignature){
                if((int)signatureSetter.invokeExact(handle,solver,2,signature)!=0)throw new IllegalStateException("Jolt bone follow update failed");
                appliedSignature=signature;
            }
            if(motion.strain!=appliedStrain){
                if((int)strainSetter.invokeExact(solver,motion.strain)!=0)throw new IllegalStateException("Jolt strain update failed");
                appliedStrain=motion.strain;
            }
            int result=(int)step.invokeExact(solver,elapsed,stiffness(values[0]),
                (float)(values[4]/100),(float)(values[5]/100),(float)(values[6]/100),(float)(values[7]/100));
            if(result!=0)throw new IllegalStateException("Chest simulation failed");
            for(int r=0;r<regions.length;r++){
                Region region=regions[r];
                if((int)delta.invokeExact(handle,r,region.positions,region.normals)!=region.count)throw new IllegalStateException("Incomplete chest deformation");
                for(int i=0;i<region.count*3;i++)if(!Float.isFinite(region.positions.getAtIndex(JAVA_FLOAT,i))||!Float.isFinite(region.normals.getAtIndex(JAVA_FLOAT,i)))throw new IllegalStateException("Non-finite chest result");
                if((int)getQuality.invokeExact(handle,r,quality)!=0||quality.getAtIndex(JAVA_DOUBLE,1)!=0)throw new IllegalStateException("Chest local inversion; use manual physics reset");
            }
        }catch(Throwable e){failed=true;error=e.toString();System.err.println("[AimohPMXPreview] Chest physics suspended; original mesh retained: "+e);}
    }
    private float transform(int region,float[] vector,int axis){
        long at=region*16L+axis;
        return frames.getAtIndex(JAVA_FLOAT,at)*vector[0]+frames.getAtIndex(JAVA_FLOAT,at+4)*vector[1]
            +frames.getAtIndex(JAVA_FLOAT,at+8)*vector[2]+frames.getAtIndex(JAVA_FLOAT,at+12);
    }
    private void evaluateInertia(float seconds)throws Throwable {
        float x=0,y=0,z=0;
        sideX=sideZ=0;
        for(int r=0;r<regions.length;r++){
            Region region=regions[r];
            x+=transform(r,region.center,0);y+=transform(r,region.center,1);z+=transform(r,region.center,2);
            sideX+=frames.getAtIndex(JAVA_FLOAT,r*16L);
            sideZ+=frames.getAtIndex(JAVA_FLOAT,r*16L+2);
        }
        float length=(float)Math.sqrt(sideX*sideX+sideZ*sideZ);
        if(length>1e-5f){sideX/=length;sideZ/=length;}
        else sideX=sideZ=0;
        motion.update(x/regions.length,y/regions.length,z/regions.length,sideX,sideZ,seconds,reset,values[9],values[10],values[11]);
        if(motion.kickX!=0||motion.kickZ!=0){
            impulseVector.setAtIndex(JAVA_FLOAT,0,motion.kickX);
            impulseVector.setAtIndex(JAVA_FLOAT,2,motion.kickZ);
            for(int r=0;r<regions.length;r++)if((int)impulse.invokeExact(handle,r,impulseVector)!=0)
                throw new IllegalStateException("Jolt lateral inertia failed");
        }
        for(int r=0;r<regions.length;r++){
            Region region=regions[r];
            region.angle=0;
            if(Math.abs(motion.swing)<1e-5||length<=1e-5f)continue;
            for(int k=0;k<3;k++)region.movingPivot[k]=transform(r,region.pivot,k)/scale;
            float dx=transform(r,region.center,0)/scale-region.movingPivot[0];
            float dz=transform(r,region.center,2)/scale-region.movingPivot[2];
            float orientation=sideZ*dx-sideX*dz;
            if(Math.abs(orientation)>1e-5)region.angle=-(float)Math.copySign(1f,orientation)*motion.swing;
        }
    }
    private void rotate(float[] points,int i,float x,float y,float z,float cosine,float sine){
        float px=points[i]-x,py=points[i+1]-y,pz=points[i+2]-z;
        float projection=(1-cosine)*(px*sideX+pz*sideZ);
        points[i]=x+cosine*px-sine*sideZ*py+projection*sideX;
        points[i+1]=y+cosine*py+sine*(sideZ*px-sideX*pz);
        points[i+2]=z+cosine*pz+sine*sideX*py+projection*sideZ;
    }
    public void deform(float[] positions,float[] normals) {
        if(!enabled||failed||closed||reset||values[3]==0)return;
        float strength=(float)(values[3]/100);
        for(Region region:regions)for(int i=0;i<region.count;i++) {
            float weight=region.weights[i]*strength;int dst=region.ids[i]*3;
            for(int k=0;k<3;k++) {
                float p=region.positions.getAtIndex(JAVA_FLOAT,i*3L+k),n=region.normals.getAtIndex(JAVA_FLOAT,i*3L+k);
                if(!Float.isFinite(p)||!Float.isFinite(n))throw new IllegalStateException("Non-finite chest mesh");
                positions[dst+k]+=weight*p/scale;normals[dst+k]+=weight*n;
            }
            float angle=region.angle*region.swingWeights[i]*strength;
            if(Math.abs(angle)>1e-5f){
                float cosine=(float)Math.cos(angle),sine=(float)Math.sin(angle);
                float[] pivot=region.movingPivot;
                rotate(positions,dst,pivot[0],pivot[1],pivot[2],cosine,sine);
                rotate(normals,dst,0,0,0,cosine,sine);
            }
        }
        for(int vertex:affected){int at=vertex*3;float x=normals[at],y=normals[at+1],z=normals[at+2];float length=(float)Math.sqrt(x*x+y*y+z*z);if(length>1e-8f){normals[at]=x/length;normals[at+1]=y/length;normals[at+2]=z/length;}}
    }

    record GpuOffsets(int[] spans,float[] rows,float axisX,float axisZ) {}

    GpuOffsets gpuOffsets(int vertices) {
        if(!enabled||failed||closed||reset||values[3]==0)return null;
        if(gpuSpans==null) {
            gpuSpans=new int[vertices+1];
            for(Region region:regions)for(int id:region.ids)gpuSpans[id+1]++;
            for(int i=1;i<gpuSpans.length;i++)gpuSpans[i]+=gpuSpans[i-1];
            int[] next=gpuSpans.clone();gpuRows=new int[gpuSpans[vertices]];
            int source=0;
            for(Region region:regions)for(int id:region.ids)gpuRows[source++]=next[id]++;
        }
        float[] rows=new float[gpuRows.length*12];
        float strength=(float)(values[3]/100);int source=0;
        for(Region region:regions)for(int i=0;i<region.count;i++) {
            int at=gpuRows[source++]*12;float weight=region.weights[i]*strength;
            for(int k=0;k<3;k++) {
                rows[at+k]=weight*region.positions.getAtIndex(JAVA_FLOAT,i*3L+k)/scale;
                rows[at+4+k]=weight*region.normals.getAtIndex(JAVA_FLOAT,i*3L+k);
                rows[at+8+k]=region.movingPivot[k];
            }
            rows[at+3]=region.angle*region.swingWeights[i]*strength;
        }
        return new GpuOffsets(gpuSpans,rows,sideX,sideZ);
    }
    public void close(){
        if(closed)return;closed=true;
        try {
            try {if(solver.address()!=0)destroy.invokeExact(solver);}
            finally {if(handle!=null&&handle.address()!=0)delete.invokeExact(handle);}
        }catch(Throwable e){throw new IllegalStateException("Cannot release chest world",e);}
        finally{arena.close();}
    }
    public static synchronized int liveWorlds(){
        try {
            return library==null?0:(int)library.call("sb_live",FunctionDescriptor.of(JAVA_INT)).invokeExact();
        }catch(Throwable e){throw new IllegalStateException(e);}
    }
}
