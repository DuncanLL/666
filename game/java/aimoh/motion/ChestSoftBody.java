package aimoh.motion;

import com.shiroha.mmdskin.NativeFunc;
import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.*;
import java.nio.file.Path;
import java.util.*;
import org.json.JSONObject;
import static java.lang.foreign.ValueLayout.*;

/** The workbench's Bullet volume solver; one owner and one final-pose step. */
public final class ChestSoftBody implements AutoCloseable {
    private static Library library;
    private static final class Library {
        final Arena arena=Arena.ofShared();
        final SymbolLookup symbols;
        Library(Path path){symbols=SymbolLookup.libraryLookup(path,arena);}
        MethodHandle call(String name,FunctionDescriptor type){return Linker.nativeLinker().downcallHandle(symbols.findOrThrow(name),type);}
    }
    public static synchronized void initialize(Path path){if(library==null)library=new Library(path);}
    public static final String[] KEYS={"softness","damping","inertia","strength"};
    private static final double[] DEFAULT={100*(.75-.45)/.67,100*(.045-.035)/.085,50,100};
    private final Arena arena=Arena.ofShared();
    private final NativeFunc api;
    private final long model;
    private final float scale;
    private final Region[] regions;
    private final int[] affected;
    private final ByteBuffer palette;
    private final MemorySegment frames,quality;
    private MemorySegment handle;
    private final MethodHandle delete,add,setFrames,step,delta,tune,getQuality;
    private final double[] values=DEFAULT.clone();
    private boolean reset=true,enabled=true,failed,closed;
    private String error="";
    private static final class Region {
        int bone,count;int[] ids;float[] weights;
        MemorySegment positions,normals;
    }

    public static ChestSoftBody load(NativeFunc api,long model,JSONObject profile)throws IOException {
        String sha=profile.getString("pmx_sha256");
        if(!sha.matches("[0-9a-f]{64}"))throw new IOException("Invalid PMX identity");
        byte[] data;
        try(var input=ChestSoftBody.class.getResourceAsStream("/aimoh/pmxpreview/chest/"+sha+".bin")){
            if(input==null)return null;data=input.readAllBytes();
        }
        if(library==null)throw new IllegalStateException("Chest native component was not initialized");
        return new ChestSoftBody(api,model,profile,data);
    }
    private ChestSoftBody(NativeFunc api,long model,JSONObject profile,byte[] data)throws IOException {
        this.api=api;this.model=model;scale=1/profile.getFloat("unit_scale");
        Library lib=library;
        delete=lib.call("sb_delete",FunctionDescriptor.ofVoid(ADDRESS));
        add=lib.call("sb_add",FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS,ADDRESS,ADDRESS,ADDRESS,ADDRESS,JAVA_INT,JAVA_INT,JAVA_FLOAT,JAVA_FLOAT,JAVA_FLOAT,JAVA_FLOAT));
        setFrames=lib.call("sb_frames",FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS,JAVA_INT));
        step=lib.call("sb_step",FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_FLOAT));
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
    public double slider(int index){return values[index];}
    public JSONObject settings(){JSONObject j=new JSONObject();for(int i=0;i<KEYS.length;i++)j.put(KEYS[i],values[i]);return j;}
    public void settings(JSONObject input){
        applySettings(prepareSettings(input));
    }
    double[] prepareSettings(JSONObject input){
        double[] next=values.clone();
        for(String key:input.keySet())if(!Arrays.asList(KEYS).contains(key))throw new IllegalArgumentException("Unknown chest setting: "+key);
        for(int i=0;i<KEYS.length;i++)if(input.has(KEYS[i]))next[i]=input.getDouble(KEYS[i]);
        for(double value:next)if(!Double.isFinite(value)||value<0||value>100)throw new IllegalArgumentException("Chest sliders must be 0..100");
        return next;
    }
    void applySettings(double[] next){
        try{
            int result=(int)tune.invokeExact(handle,(float)Math.clamp(.75-.67*next[0]/100,.08,.75),(float)(.035+.085*next[1]/100),(float)(.6+.8*next[2]/100));
            if(result!=0)throw new IllegalStateException("Chest parameter update failed");
            System.arraycopy(next,0,values,0,values.length);
        }catch(Throwable e){throw new IllegalStateException("Chest parameter update failed",e);}
    }
    public void slider(int index,double value){if(index<0||index>=KEYS.length)throw new IllegalArgumentException("Invalid chest slider");settings(new JSONObject().put(KEYS[index],value));}
    public void defaults(){JSONObject j=new JSONObject();for(int i=0;i<KEYS.length;i++)j.put(KEYS[i],DEFAULT[i]);settings(j);}
    public void enabled(boolean value){if(enabled!=value){enabled=value;reset=true;}}
    public void reset(){reset=true;failed=false;error="";}
    public void evaluate(float seconds) {
        if(!enabled||failed||closed)return;
        try{
            palette.clear();if(api.CopySkinningMatricesToBuffer(model,palette)!=palette.capacity()/64)throw new IllegalStateException("Incomplete chest palette");
            for(int r=0;r<regions.length;r++)for(int i=0;i<16;i++){
                float value=palette.getFloat(regions[r].bone*64+i*4);if(i>=12&&i<15)value*=scale;
                frames.setAtIndex(JAVA_FLOAT,r*16L+i,value);
            }
            if((int)setFrames.invokeExact(handle,frames,reset?1:0)!=0)throw new IllegalStateException("Invalid chest pose");reset=false;
            if((int)step.invokeExact(handle,Math.clamp(seconds,0f,.08f))!=0)throw new IllegalStateException("Chest simulation failed");
            for(int r=0;r<regions.length;r++){
                Region region=regions[r];
                if((int)delta.invokeExact(handle,r,region.positions,region.normals)!=region.count)throw new IllegalStateException("Incomplete chest deformation");
                for(int i=0;i<region.count*3;i++)if(!Float.isFinite(region.positions.getAtIndex(JAVA_FLOAT,i))||!Float.isFinite(region.normals.getAtIndex(JAVA_FLOAT,i)))throw new IllegalStateException("Non-finite chest result");
                if((int)getQuality.invokeExact(handle,r,quality)!=0||quality.getAtIndex(JAVA_DOUBLE,1)!=0)throw new IllegalStateException("Chest local inversion; use manual physics reset");
            }
        }catch(Throwable e){failed=true;error=e.toString();System.err.println("[AimohPMXPreview] Chest physics suspended; original mesh retained: "+e);}
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
        }
        for(int vertex:affected){int at=vertex*3;float x=normals[at],y=normals[at+1],z=normals[at+2];float length=(float)Math.sqrt(x*x+y*y+z*z);if(length>1e-8f){normals[at]=x/length;normals[at+1]=y/length;normals[at+2]=z/length;}}
    }
    public void close(){if(closed)return;closed=true;try{if(handle!=null&&handle.address()!=0)delete.invokeExact(handle);}catch(Throwable e){throw new IllegalStateException("Cannot release chest world",e);}finally{arena.close();}}
    public static int liveWorlds(){if(library==null)return 0;try{return(int)library.call("sb_live",FunctionDescriptor.of(JAVA_INT)).invokeExact();}catch(Throwable e){throw new IllegalStateException(e);}}
}
