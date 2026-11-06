package viewpoint.render;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import viewpoint.platform.GlProgram;
import org.lwjgl.opengl.*;

/** Shared Eclipse -> original Unity SH register adapter; runs once per frame.
 * Uses GPU copies into per-draw banks, with no per-pixel integrations or readback.
 */
public final class AimohGf2EclipseEnvironment implements AutoCloseable {
    public final int buffer;
    public final int reflectionTexture;
    public final int exposureTexture=GL45.glCreateTextures(GL31.GL_TEXTURE_BUFFER);
    public int exposureFloatOffset;
    private int exposureBuffer;
    private GlProgram localProbe;
    private int actorBuffer;
    public boolean localProbeActive;
    public int nativeProbeGrids;
    public String probeEvidence="{}";
    private final int program,scaleLocation,nativeFlagLocation,nativeOffsetLocation,nativeScaleLocation;
    private final int cubeProgram,cubeLevelLocation;
    private long uploaded=Long.MIN_VALUE;
    public AimohGf2EclipseEnvironment(Path source) throws IOException {
        this(source,null);
    }
    public AimohGf2EclipseEnvironment(Path source,String cubeSource) throws IOException {
        this(Files.readString(source),cubeSource);
    }
    public AimohGf2EclipseEnvironment(String source,String cubeSource) {
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);GL20.glShaderSource(shader,source);GL20.glCompileShader(shader);
        if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0){String log=GL20.glGetShaderInfoLog(shader);GL20.glDeleteShader(shader);throw new IllegalStateException(log);}
        program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);GL20.glLinkProgram(program);GL20.glDeleteShader(shader);
        if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
        scaleLocation=GL20.glGetUniformLocation(program,"sourceEnvironmentScale");int previous=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        nativeFlagLocation=GL20.glGetUniformLocation(program,"copyNativeMain");nativeOffsetLocation=GL20.glGetUniformLocation(program,"nativeMainFloatOffset");nativeScaleLocation=GL20.glGetUniformLocation(program,"nativeMainScale");
        GL20.glUseProgram(program);GL20.glUniform1i(GL20.glGetUniformLocation(program,"sourceSkySH"),0);GL20.glUseProgram(previous);
        buffer=GL15.glGenBuffers();int old=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,128,GL15.GL_DYNAMIC_DRAW);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,old);
        if(cubeSource==null){cubeProgram=0;cubeLevelLocation=-1;reflectionTexture=0;}
        else{
            cubeProgram=compileCompute(cubeSource);cubeLevelLocation=GL20.glGetUniformLocation(cubeProgram,"level");GL20.glUseProgram(cubeProgram);GL20.glUniform1i(GL20.glGetUniformLocation(cubeProgram,"sourceSky"),0);GL20.glUseProgram(previous);
            int bound=GL11.glGetInteger(GL13.GL_TEXTURE_BINDING_CUBE_MAP);reflectionTexture=GL11.glGenTextures();GL11.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP,reflectionTexture);GL42.glTexStorage2D(GL13.GL_TEXTURE_CUBE_MAP,7,GL30.GL_RGBA16F,64,64);
            GL11.glTexParameteri(GL13.GL_TEXTURE_CUBE_MAP,GL12.GL_TEXTURE_MAX_LEVEL,6);GL11.glTexParameteri(GL13.GL_TEXTURE_CUBE_MAP,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR_MIPMAP_LINEAR);GL11.glTexParameteri(GL13.GL_TEXTURE_CUBE_MAP,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);
            GL11.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP,bound);
        }
    }
    static int compileCompute(String source){
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);GL20.glShaderSource(shader,source);GL20.glCompileShader(shader);
        if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0){String log=GL20.glGetShaderInfoLog(shader);GL20.glDeleteShader(shader);throw new IllegalStateException(log);}
        int program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);GL20.glLinkProgram(program);GL20.glDeleteShader(shader);
        if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0){String log=GL20.glGetProgramInfoLog(program);GL20.glDeleteProgram(program);throw new IllegalStateException(log);}return program;
    }
    /** A zero-copy view of the actual host exposure field; no CPU readback. */
    public void exposureView(int nativeBuffer,int byteOffset){
        if(nativeBuffer<=0||byteOffset<0||byteOffset%4!=0)throw new IllegalArgumentException("Invalid native exposure field");
        // Texture-buffer views retain deleted objects; GL can reuse their names.
        GL45.glTextureBuffer(exposureTexture,GL30.GL_R32F,nativeBuffer);exposureBuffer=nativeBuffer;
        exposureFloatOffset=byteOffset/4;
        GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
    }
    public void configureLocalProbe(String source){
        if(localProbe!=null)throw new IllegalStateException("Native probe already configured");
        localProbe=GlProgram.compute("GF2 native spatial sky probe",source,List.of());localProbe.sampler("uBandLight",4);localProbe.sampler("uLight",5);localProbe.sampler("gf2NativeExposure",6);
        actorBuffer=GL45.glCreateBuffers();GL45.glNamedBufferData(actorBuffer,144,GL15.GL_DYNAMIC_DRAW);
    }
    /** Reuse native chunk skylight, with the same per-object probe semantics as Unity SH. */
    public void probeActor(float[] anchor,float[] cameraPosition,float[] minimumLight,float[] skyLevel,boolean capture){
        if(minimumLight.length!=3)throw new IllegalArgumentException("Native minimum-light field shape");
        for(float value:minimumLight)if(!Float.isFinite(value)||value<0)throw new IllegalArgumentException("Native minimum-light value");
        if(skyLevel.length!=3)throw new IllegalArgumentException("Native sky-level field shape");
        for(float value:skyLevel)if(!Float.isFinite(value)||value<0)throw new IllegalArgumentException("Native sky-level value");
        nativeProbeGrids=BandLight.grids();localProbeActive=localProbe!=null;
        if(!localProbeActive){probeEvidence="{}";return;}
        int oldProgram=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0+4);int oldBand=GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D),sampler4=GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,4);
        GL13.glActiveTexture(GL13.GL_TEXTURE0+5);int oldLight=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),sampler5=GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,5);
        GL13.glActiveTexture(GL13.GL_TEXTURE0+6);int oldExposure=GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER),sampler6=GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,6);
        int generic=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);int[] ids=new int[2];long[] starts=new long[2],sizes=new long[2];
        for(int i=0;i<2;i++){ids[i]=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,i);starts[i]=GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START,i);sizes[i]=GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE,i);}
        try{
            localProbe.use();localProbe.set("actorAnchor",anchor[0],anchor[1],anchor[2]);localProbe.set("cameraPosition",cameraPosition[0],cameraPosition[1],cameraPosition[2]);
            localProbe.setInt("nativeSkyAvailable",nativeProbeGrids>0?1:0);localProbe.set("nativeMinimumLight",minimumLight[0],minimumLight[1],minimumLight[2]);
            localProbe.set("uSkyLevel",skyLevel[0],skyLevel[1],skyLevel[2]);
            if(exposureBuffer<=0)throw new IllegalStateException("Native exposure must be bound before the source light probe");
            localProbe.setInt("gf2NativeExposureOffset",exposureFloatOffset);GL13.glActiveTexture(GL13.GL_TEXTURE0+6);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,exposureTexture);GL33.glBindSampler(6,0);
            MeshArena.bindLight(5,localProbe);BandLight.bind(4);GL33.glBindSampler(4,0);GL33.glBindSampler(5,0);
            if(capture){
                org.json.JSONObject evidence=new org.json.JSONObject();int id=localProbe.id();
                for(String name:List.of("actorAnchor","cameraPosition","uLightAtlasSize","nativeMinimumLight","uSkyLevel")){
                    int location=GL20.glGetUniformLocation(id,name);if(location<0)continue;
                    var values=org.lwjgl.BufferUtils.createFloatBuffer(4);GL20.glGetUniformfv(id,location,values);int count=name.equals("uLightAtlasSize")?2:3;float[] actual=new float[count];for(int i=0;i<count;i++)actual[i]=values.get(i);evidence.put(name,actual);
                }
                evidence.put("uLight",GL20.glGetUniformi(id,GL20.glGetUniformLocation(id,"uLight"))).put("uBandLight",GL20.glGetUniformi(id,GL20.glGetUniformLocation(id,"uBandLight"))).put("nativeSkyAvailable",nativeProbeGrids>0);
                GL13.glActiveTexture(GL13.GL_TEXTURE0+4);int bandTexture=GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);GL13.glActiveTexture(GL13.GL_TEXTURE0+5);int lightTexture=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);evidence.put("band_texture",bandTexture).put("light_texture",lightTexture);
                int chunkX=(int)Math.floor(-(anchor[0]+cameraPosition[0])/8),chunkY=(int)Math.floor(-(anchor[2]+cameraPosition[2])/8),level=(int)Math.floor((anchor[1]+cameraPosition[1]-64)/2.4494896);evidence.put("native_chunk",new int[]{chunkX,chunkY,level});
                if(level>=0&&level<32){var texel=org.lwjgl.BufferUtils.createIntBuffer(1);GL45.glGetTextureSubImage(bandTexture,0,Math.floorMod(chunkX,72),Math.floorMod(chunkY,72),level,1,1,1,GL30.GL_RED_INTEGER,GL11.GL_INT,texel);int slot=texel.get(0)-1;evidence.put("slot",slot);
                    if(slot>=0){int x=(slot%204)*20+3,y=(slot/204)*40+23;var sky=org.lwjgl.BufferUtils.createFloatBuffer(4);GL45.glGetTextureSubImage(lightTexture,0,x,y,0,1,1,1,GL11.GL_RGBA,GL11.GL_FLOAT,sky);evidence.put("sky_texel",new float[]{sky.get(0),sky.get(1),sky.get(2),sky.get(3)});}}
                probeEvidence=evidence.toString();
            }
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,buffer);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,actorBuffer);GL43.glDispatchCompute(1,1,1);
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        }finally{
            for(int i=0;i<2;i++){if(ids[i]!=0&&sizes[i]>0)GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,i,ids[i],starts[i],sizes[i]);else GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,i,ids[i]);}
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,generic);GL13.glActiveTexture(GL13.GL_TEXTURE0+4);GL11.glBindTexture(GL12.GL_TEXTURE_3D,oldBand);GL33.glBindSampler(4,sampler4);
            GL13.glActiveTexture(GL13.GL_TEXTURE0+5);GL11.glBindTexture(GL11.GL_TEXTURE_2D,oldLight);GL33.glBindSampler(5,sampler5);GL13.glActiveTexture(GL13.GL_TEXTURE0+6);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,oldExposure);GL33.glBindSampler(6,sampler6);GL13.glActiveTexture(active);GL20.glUseProgram(oldProgram);
        }
    }
    public int localProbeBuffer(){return actorBuffer;}
    public void update(int nativeSkySH,float sharedScale,long revision){
        updateWithMain(nativeSkySH,sharedScale,0,0,0,revision);
    }
    public void updateWithMain(int nativeSkySH,float sharedScale,int nativeBuffer,int nativeByteOffset,float mainScale,long revision){
        updateFrame(nativeSkySH,0,sharedScale,nativeBuffer,nativeByteOffset,mainScale,revision);
    }
    public void updateFrame(int nativeSkySH,int nativeRoughSky,float sharedScale,int nativeBuffer,int nativeByteOffset,float mainScale,long revision){
        if(revision==uploaded)return;
        if(nativeSkySH<=0||!Float.isFinite(sharedScale)||sharedScale<0)throw new IllegalArgumentException("Actual native sky coefficients and shared environment scale required");
        int previous=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int storage=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,0);
        int storage1=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,1);long storageStart=GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START,1),storageSize=GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE,1);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);int oldTexture=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),sampler=GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,0);
        try {
            GL20.glUseProgram(program);GL20.glUniform1f(scaleLocation,sharedScale);GL11.glBindTexture(GL11.GL_TEXTURE_2D,nativeSkySH);GL33.glBindSampler(0,0);
            if(nativeByteOffset<0||nativeByteOffset%4!=0||!Float.isFinite(mainScale))throw new IllegalArgumentException("Invalid native main-light field");
            GL20.glUniform1i(nativeFlagLocation,nativeBuffer>0?1:0);GL20.glUniform1i(nativeOffsetLocation,nativeByteOffset/4);GL20.glUniform1f(nativeScaleLocation,mainScale);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,nativeBuffer);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,buffer);GL43.glDispatchCompute(1,1,1);
            if(nativeRoughSky>0){
                if(cubeProgram==0)throw new IllegalStateException("Reflection adapter not initialized");
                GL20.glUseProgram(cubeProgram);GL11.glBindTexture(GL11.GL_TEXTURE_2D,nativeRoughSky);
                for(int level=0;level<7;level++){GL20.glUniform1i(cubeLevelLocation,level);GL42.glBindImageTexture(0,reflectionTexture,level,true,0,GL15.GL_WRITE_ONLY,GL30.GL_RGBA16F);int size=Math.max(1,64>>level);GL43.glDispatchCompute((size+7)/8,(size+7)/8,6);}
                GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
            }
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);uploaded=revision;
        } finally {
            if(storage1!=0&&storageSize>0)GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,1,storage1,storageStart,storageSize);else GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,storage1);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,storage);GL11.glBindTexture(GL11.GL_TEXTURE_2D,oldTexture);GL33.glBindSampler(0,sampler);
            GL13.glActiveTexture(active);GL20.glUseProgram(previous);
        }
    }
    public void copyTo(int drawRegisterBuffer,int shByteOffset){
        copyRange(drawRegisterBuffer,0,shByteOffset,112,localProbeActive?actorBuffer:buffer);
    }
    public void copyMainTo(int drawRegisterBuffer,int byteOffset){copyRange(drawRegisterBuffer,112,byteOffset,16,buffer);}
    public void copySpecTo(int drawRegisterBuffer,int byteOffset){if(localProbeActive)copyRange(drawRegisterBuffer,112,byteOffset,16,actorBuffer);}
    public void copyCameraTo(int drawRegisterBuffer,int byteOffset){if(localProbeActive)copyRange(drawRegisterBuffer,128,byteOffset,16,actorBuffer);}
    private void copyRange(int drawRegisterBuffer,int sourceByteOffset,int shByteOffset,int count,int sourceBuffer){
        if(uploaded==Long.MIN_VALUE||drawRegisterBuffer<=0||shByteOffset<0)throw new IllegalStateException("Original SH copy before native environment update");
        GL45.glCopyNamedBufferSubData(sourceBuffer,drawRegisterBuffer,sourceByteOffset,shByteOffset,count);
    }
    @Override public void close(){GL15.glDeleteBuffers(buffer);GL20.glDeleteProgram(program);if(cubeProgram!=0)GL20.glDeleteProgram(cubeProgram);if(reflectionTexture!=0)GL11.glDeleteTextures(reflectionTexture);GL11.glDeleteTextures(exposureTexture);if(localProbe!=null)localProbe.delete();if(actorBuffer!=0)GL15.glDeleteBuffers(actorBuffer);}
}
