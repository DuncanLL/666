package viewpoint.render;
import java.lang.reflect.*;
import java.util.*;
import org.joml.Matrix4f;
import org.lwjgl.opengl.*;
import viewpoint.platform.GlProgram;
import viewpoint.platform.AimohNativePackSelection;

/** Native default lighting resources feed source registers; no scene light/post overrides. */
public final class AimohNativeGf2Environment implements AutoCloseable {
    public int buffer,cube,shadowSampler;
    private GlProgram pack,reflection,prefilter;
    private AimohNativeRoomProbe roomProbe;
    private GlProgram lighting;
    public final float[] direction=new float[3],level=new float[3],sunColor=new float[3];
    public float strength;
    private final float[] shadowMatrices=new float[48],shadowParams=new float[12];
    private int[] shadowViews;
    private int shadowSize;
    private float[] lastEngine;
    private int reflectionUpdates;
    private final Map<Integer,Integer> reflectionCubes=new HashMap<>();
    public AimohNativeGf2Environment()throws Exception{
        try{
        pack=AimohNativePackSelection.compute("GF2 Native SH register transfer","gf2/native_light.comp");
        reflection=AimohNativePackSelection.compute("GF2 Native default reflection cube","gf2/native_cube.comp");
        reflection.sampler("uDepth",0);reflection.sampler("gf2ProbeScene",4);reflection.sampler("gf2ProbeAlbedo",5);reflection.sampler("gf2ProbeRoom",3);
        // Ultra cloud reflections consume the same weather/noise inputs as the stock sky.
        WeatherMap.samplers(reflection);
        prefilter=AimohNativePackSelection.compute("GF2 source GGX reflection filtering","gf2/native_prefilter.comp");prefilter.sampler("nativeRawCube",0);
        if(pack.id()==0||reflection.id()==0||prefilter.id()==0)throw new IllegalStateException("Native light/cube/filter program missing: "+GlProgram.lastError());
        buffer=GL45.glCreateBuffers();GL45.glNamedBufferData(buffer,128L,GL15.GL_DYNAMIC_DRAW);
        cube=environmentCube();
        shadowSampler=GL33.glGenSamplers();for(int[] p:new int[][]{{GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR},{GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR},{GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE},{GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE},{GL14.GL_TEXTURE_COMPARE_MODE,GL30.GL_COMPARE_REF_TO_TEXTURE},{GL14.GL_TEXTURE_COMPARE_FUNC,GL11.GL_LEQUAL}})GL33.glSamplerParameteri(shadowSampler,p[0],p[1]);
        }catch(Exception e){close();throw e;}
    }
    private static int environmentCube(){
        int texture=GL45.glCreateTextures(GL13.GL_TEXTURE_CUBE_MAP);GL45.glTextureStorage2D(texture,7,GL30.GL_RGBA16F,64,64);GL45.glTextureParameteri(texture,GL12.GL_TEXTURE_MAX_LEVEL,6);GL45.glTextureParameteri(texture,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR_MIPMAP_LINEAR);GL45.glTextureParameteri(texture,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);
        for(int axis:new int[]{GL11.GL_TEXTURE_WRAP_S,GL11.GL_TEXTURE_WRAP_T,GL12.GL_TEXTURE_WRAP_R})GL45.glTextureParameteri(texture,axis,GL12.GL_CLAMP_TO_EDGE);return texture;
    }
    static Object world(String name)throws Exception{Field f=WorldRenderer.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    static Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private void find()throws Exception{
        if(lighting!=null)return;Object surface=world("surfaces");
        for(Field f:surface.getClass().getDeclaredFields())if(f.getType()==GlProgram.class){f.setAccessible(true);GlProgram p=(GlProgram)f.get(surface);if(p.has("uEngineLight")&&GL31.glGetUniformBlockIndex(p.id(),"SkyLight")!=GL31.GL_INVALID_INDEX){lighting=p;break;}}
        if(lighting==null)throw new IllegalStateException("Native source lighting unavailable");
    }
    private void uniform(String name,float[] result){int location=GL20.glGetUniformLocation(lighting.id(),name);if(location<0)throw new IllegalStateException("Native light uniform absent: "+name);GL20.glGetUniformfv(lighting.id(),location,result);}
    public void update(FrameContext frame)throws Exception{
        find();uniform("uSunDir",direction);uniform("uSkyLevel",level);uniform("uSunColor",sunColor);strength=GL20.glGetUniformf(lighting.id(),GL20.glGetUniformLocation(lighting.id(),"uSunStrength"));
        int block=GL31.glGetUniformBlockIndex(lighting.id(),"SkyLight"),binding=GL31.glGetActiveUniformBlocki(lighting.id(),block,GL31.GL_UNIFORM_BLOCK_BINDING),sky=GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING,binding);
        if(sky==0)throw new IllegalStateException("Actual Native SkyLight UBO is not bound");
        pack.use();pack.set("nativeRelativeSky",level[0],level[1],level[2]);pack.set("nativePrimaryStrength",strength);
        GL31.glUniformBlockBinding(pack.id(),GL31.glGetUniformBlockIndex(pack.id(),"NativeSkyLight"),binding);
        // The first actor writes the buffer before any copy or draw consumes it.
        lastEngine=null;
        reflection.use();reflection.set("uEye",frame.eyeX,frame.eyeY,frame.eyeZ);reflection.set("uSunDir",direction[0],direction[1],direction[2]);reflection.set("uSunColor",sunColor[0],sunColor[1],sunColor[2]);reflection.set("uSunStrength",strength);
        for(int i=0;i<3;i++){
            float[] matrix=new float[16],params=new float[4];uniform("uShadowMatrix["+i+"]",matrix);uniform("uShadowMaps["+i+"]",params);System.arraycopy(matrix,0,shadowMatrices,i*16,16);System.arraycopy(params,0,shadowParams,i*4,4);
        }
        Object shadows=world("shadows");shadowViews=(int[])field(shadows,"views");shadowSize=(Integer)field(shadows,"size");
    }
    public void reflection(FrameContext frame,float[] center,float skyShare,int actor)throws Exception{
        cube=reflectionCubes.computeIfAbsent(actor,k->reflectionCubes.isEmpty()&&cube!=0?cube:environmentCube());
        boolean room=frame.pipeline.reflections;
        if(room){if(roomProbe==null)roomProbe=new AimohNativeRoomProbe();roomProbe.capture(frame,center);roomProbe.illuminate(frame,center);}
        Targets targets=(Targets)world("targets");reflection.use();frame.camera(reflection);
        reflection.set("gf2ProbeViewProjection",frame.viewProjection);
        reflection.set("gf2ProbePosition",center[0],center[1],center[2]);reflection.set("gf2ProbeSkyShare",skyShare);
        reflection.setInt("gf2ProbeSceneOn",frame.pipeline.reflections?1:0);
        reflection.setInt("gf2ProbeRoomOn",room?1:0);
        int[] textures={targets.depth,targets.hdr,targets.albedo};
        int[] units={0,4,5};for(int i=0;i<textures.length;i++){GL13.glActiveTexture(GL13.GL_TEXTURE0+units[i]);GL11.glBindTexture(GL11.GL_TEXTURE_2D,textures[i]);GL33.glBindSampler(units[i],0);}
        GL13.glActiveTexture(GL13.GL_TEXTURE0+3);GL11.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP,room?roomProbe.radiance:0);GL33.glBindSampler(3,0);
        GL42.glBindImageTexture(0,cube,0,true,0,GL15.GL_WRITE_ONLY,GL30.GL_RGBA16F);GL43.glDispatchCompute(8,8,6);
        GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
        // The shader fetches only LOD 0. It writes a different mip on each dispatch.
        prefilter.use();GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP,cube);GL33.glBindSampler(0,0);
        for(int mip=1;mip<=6;mip++){int side=64>>mip;prefilter.set("sourcePerceptualRoughness",mip/6f);GL42.glBindImageTexture(0,cube,mip,true,0,GL15.GL_WRITE_ONLY,GL30.GL_RGBA16F);GL43.glDispatchCompute((side+7)/8,(side+7)/8,6);}GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
        reflectionUpdates++;
    }
    public int reflectionUpdates(){return reflectionUpdates;}
    public int earlyReflection(){if(cube==0)cube=environmentCube();return cube;}
    public void retainActors(Set<Integer> alive){
        for(var it=reflectionCubes.entrySet().iterator();it.hasNext();){var entry=it.next();if(!alive.contains(entry.getKey())){if(cube==entry.getValue())cube=0;GL11.glDeleteTextures(entry.getValue());it.remove();}}
    }
    public org.json.JSONObject evidence(){return new org.json.JSONObject().put("main_direction",direction).put("main_colour",sunColor).put("native_sky_level",level).put("main_strength",strength).put("actor_engine_light",lastEngine==null?org.json.JSONObject.NULL:lastEngine).put("reflection_updates",reflectionUpdates).put("actor_reflection_cubes",reflectionCubes.size()).put("room_probe_captures",roomProbe==null?0:roomProbe.captures()).put("room_probe_bytes",roomProbe==null?0:roomProbe.bytes()).put("reflection_scope","Actual six-direction available Native scene geometry and selected Native lighting, plus current visible scene and Native sky. Transparent transmission, unloaded geometry, dynamic character/vehicle coverage and real-game cost still need validation.");}
    public record Shadow(int texture,int size,float[] sourceMatrices,boolean enabled){}
    public void prepareDepth(FrameContext frame)throws Exception{
        direction[0]=frame.scene.sunX;direction[1]=frame.scene.sunY;direction[2]=frame.scene.sunZ;strength=frame.sun?frame.scene.sunStrength:0;
        Object shadows=world("shadows");Matrix4f[] matrices=(Matrix4f[])field(shadows,"matrix");float[] reach=(float[])field(shadows,"reach");shadowViews=(int[])field(shadows,"views");shadowSize=(Integer)field(shadows,"size");
        for(int i=0;i<3;i++){matrices[i].get(shadowMatrices,i*16);shadowParams[i*4]=i<frame.pipeline.shadowCascades?2*reach[i]/shadowSize:0;}
    }
    public Shadow shadow(float[] center,float[] eye){
        int selected=0;boolean valid=false;
        for(int i=0;i<3;i++)if(shadowParams[i*4]>0){
            var p=new org.joml.Vector4f(center[0],center[1],center[2],1);new Matrix4f().set(Arrays.copyOfRange(shadowMatrices,i*16,i*16+16)).transform(p);
            if(p.x>.01&&p.x<.99&&p.y>.01&&p.y<.99){selected=i;valid=true;break;}
        }
        Matrix4f matrix=new Matrix4f().set(Arrays.copyOfRange(shadowMatrices,selected*16,selected*16+16)).translate(eye[0],eye[1],eye[2]).scale(-1,1,-1);
        float[] result=new float[80];matrix.get(result);return new Shadow(shadowViews[selected],shadowSize,result,valid&&!WorldRenderer.off(5)&&strength>.02f);
    }
    public void copySH(int bank,int offset){GL45.glCopyNamedBufferSubData(buffer,bank,0,offset,112);}
    public void copyMain(int bank,int offset){GL45.glCopyNamedBufferSubData(buffer,bank,112,offset,16);}
    public void bindMain(GlProgram program,FrameContext frame)throws Exception{
        program.use();ShadowPass.samplers(program);ShadowPass shadows=(ShadowPass)world("shadows");
        shadows.sunUniforms(program,frame);shadows.bind(true);
    }
    public void bindIndirect(GlProgram program,FrameContext frame)throws Exception{
        if(!program.has("gf2NativeIndirectOn"))return;
        program.use();program.setInt("gf2NativeIndirectOn",WorldRenderer.off(2)?0:1);program.sampler("gf2NativeNormals",10);
        FrameUniforms.apply(program,frame);Targets targets=(Targets)world("targets");GL13.glActiveTexture(GL13.GL_TEXTURE0+10);GL11.glBindTexture(GL11.GL_TEXTURE_2D,targets.normal);GL33.glBindSampler(10,0);
    }
    public void actorLight(float[] engine){
        if(engine.length!=4)throw new IllegalArgumentException("Native actor light record differs");for(int i=0;i<4;i++)if(!Float.isFinite(engine[i])||i<3&&engine[i]<0)throw new IllegalArgumentException("Invalid Native actor light");if(engine[3]<-.0625f||engine[3]>1)throw new IllegalArgumentException("Invalid Native actor sky share");
        if(Arrays.equals(engine,lastEngine))return;lastEngine=engine.clone();pack.use();pack.set("nativeEngineLight",engine[0],engine[1],engine[2],engine[3]);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,buffer);GL43.glDispatchCompute(1,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
    }
    @Override public void close(){if(roomProbe!=null)roomProbe.close();roomProbe=null;AimohNativeSourceScope.forget(pack,reflection,prefilter);if(pack!=null)pack.delete();if(reflection!=null)reflection.delete();if(prefilter!=null)prefilter.delete();GL15.glDeleteBuffers(buffer);if(reflectionCubes.isEmpty()&&cube!=0)GL11.glDeleteTextures(cube);for(int texture:reflectionCubes.values())GL11.glDeleteTextures(texture);reflectionCubes.clear();GL33.glDeleteSamplers(shadowSampler);buffer=cube=shadowSampler=0;}
}
