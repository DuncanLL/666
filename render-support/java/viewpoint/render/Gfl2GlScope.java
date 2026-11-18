package viewpoint.render;

import org.lwjgl.opengl.*;

/** Boundaries between host draws and the original-material passes. */
public final class Gfl2GlScope implements AutoCloseable {
    enum Phase { SHADOW, PREPASS, COLOUR, PROBE_GEOMETRY, PROBE_LIGHT, SETUP, ECLIPSE, GLASS }
    private static final int D2=1, CUBE=2, BUFFER=4, ARRAY=8, D3=16, D1=32, SAMPLER=64;
    private static final int[] TARGETS={GL11.GL_TEXTURE_2D,GL13.GL_TEXTURE_CUBE_MAP,GL31.GL_TEXTURE_BUFFER,GL30.GL_TEXTURE_2D_ARRAY,GL12.GL_TEXTURE_3D,GL11.GL_TEXTURE_1D};
    private static final int[] BINDINGS={GL11.GL_TEXTURE_BINDING_2D,GL13.GL_TEXTURE_BINDING_CUBE_MAP,GL31.GL_TEXTURE_BINDING_BUFFER,GL30.GL_TEXTURE_BINDING_2D_ARRAY,GL12.GL_TEXTURE_BINDING_3D,GL11.GL_TEXTURE_BINDING_1D};
    private static final int[] IMAGE_KEYS={GL42.GL_IMAGE_BINDING_NAME,GL42.GL_IMAGE_BINDING_LEVEL,GL42.GL_IMAGE_BINDING_LAYERED,GL42.GL_IMAGE_BINDING_LAYER,GL42.GL_IMAGE_BINDING_ACCESS,GL42.GL_IMAGE_BINDING_FORMAT};
    private static final boolean VERIFY=Boolean.getBoolean("gfl2.verifyGlScope");
    private static final ThreadLocal<Gfl2GlScope> CURRENT=new ThreadLocal<>();
    private record ContextLimits(GLCapabilities context,int textureUnits,boolean compatibility){}
    private static final ThreadLocal<ContextLimits> LIMITS=new ThreadLocal<>();
    private static ContextLimits limits(){
        GLCapabilities caps=GL.getCapabilities();
        ContextLimits prior=LIMITS.get();
        if(prior==null||prior.context()!=caps){
            prior=new ContextLimits(caps,GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS),
                (GL11.glGetInteger(GL32.GL_CONTEXT_PROFILE_MASK)&GL32.GL_CONTEXT_COMPATIBILITY_PROFILE_BIT)!=0);
            LIMITS.set(prior);
        }
        return prior;
    }
    private final Gfl2GlScope enclosing=CURRENT.get();
    private final Phase phase;
    private final Gfl2ScopeAudit audit;
    private final int program,vao,draw,read,active,arrayBuffer,uniformBuffer,storageBuffer,copyRead,copyWrite,indirectBuffer;
    private final int[] unitMask, textures, ubos,ssbos,images;
    private final long[] uboRanges,ssboRanges;
    private final int[] uboSlots,ssboSlots;
    private final Gfl2FixedState explicit;
    private final boolean seamless,depthClamp;
    private final int readBuffer;
    private static final int[] SHADER_UBOS={3,8,9,10,11,12,13}, NATIVE_UBOS={0,2,3,8,9,10,11,12,13};
    private static final int[] MODEL_SSBOS={0,7,8,9,10,12,13}, GLASS_SSBOS={0,1,2,3,7,8,9,10,12,13};
    private static final int[] PROBE_UBOS={2,3}, PROBE_SSBOS={7,8,9,10,12,13};

    public Gfl2GlScope(){this(Phase.ECLIPSE);}
    public Gfl2GlScope(boolean glass){this(glass?Phase.GLASS:Phase.ECLIPSE);}
    Gfl2GlScope(boolean glass,int images){this(glass?Phase.GLASS:Phase.COLOUR);}
    Gfl2GlScope(Phase phase){
        this.phase=phase;
        audit=VERIFY?new Gfl2ScopeAudit():null;
        program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);vao=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        draw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);read=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);arrayBuffer=GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        uniformBuffer=GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_BINDING);storageBuffer=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        copyRead=GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER);copyWrite=GL11.glGetInteger(GL31.GL_COPY_WRITE_BUFFER);
        indirectBuffer=GL11.glGetInteger(GL40.GL_DRAW_INDIRECT_BUFFER_BINDING);
        readBuffer=GL11.glGetInteger(GL11.GL_READ_BUFFER);
        depthClamp=GL11.glIsEnabled(GL32.GL_DEPTH_CLAMP);
        seamless=GL11.glIsEnabled(GL32.GL_TEXTURE_CUBE_MAP_SEAMLESS);
        // ShaderLab uses broadcast blend/mask commands. The compatibility attribute stack
        // preserves every indexed colour state they change, regardless of attached MRT count.
        ContextLimits limits=limits();
        if(limits.compatibility()){
            GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT|GL11.GL_STENCIL_BUFFER_BIT|
                    GL11.GL_POLYGON_BIT|GL11.GL_SCISSOR_BIT|GL11.GL_VIEWPORT_BIT|GL13.GL_MULTISAMPLE_BIT);
            explicit=null;
        } else explicit=new Gfl2FixedState();
        int limit=limits.textureUnits();
        unitMask=new int[limit];textures=new int[limit*(TARGETS.length+1)];
        save(0,D2|SAMPLER);save(91,D2);save(92,D2);
        boolean probe=phase==Phase.PROBE_LIGHT||phase==Phase.PROBE_GEOMETRY;
        if(!probe){
            save(126,D2);
            // ModelPass.end clears all native model samplers; LayeredLooks.bind may populate these six targets.
            for(int u=64;u<80;u++)save(u,SAMPLER);
            for(int u=143;u<=148;u++)save(u,u==145?D2:ARRAY);
        }
        switch(phase){
            case SHADOW, PREPASS -> { }
            case PROBE_GEOMETRY -> {
                // Meshes.drawPlanned may include floors, plants, packed props and corpse cards.
                save(5,D2);save(41,ARRAY|SAMPLER);save(48,BUFFER);
                save(88,BUFFER);save(89,BUFFER);save(90,BUFFER);save(136,ARRAY);
            }
            case PROBE_LIGHT, COLOUR, SETUP -> {
                save(0,CUBE);save(3,CUBE);save(4,D2|SAMPLER);save(5,D2|SAMPLER);
                save(6,ARRAY);save(14,D2);save(18,D3);save(21,ARRAY);save(22,D2);save(24,D2);
                save(30,ARRAY);save(49,ARRAY);for(int u=81;u<=83;u++)save(u,D2|SAMPLER);save(105,D2|SAMPLER);
                for(int u=9;u<=12;u++)save(u,D2|SAMPLER);
                save(119,D2|SAMPLER);save(120,D2|SAMPLER);save(130,D2|SAMPLER);

            }
            case ECLIPSE -> {
                save(0,CUBE);save(4,D3|SAMPLER);save(5,D2|SAMPLER);save(6,D2|ARRAY|SAMPLER);
                save(41,ARRAY);save(49,ARRAY);save(94,D2|BUFFER|SAMPLER);save(95,D2|BUFFER|SAMPLER);
                for(int u=112;u<126;u++)save(u,D2|CUBE|SAMPLER);
            }
            case GLASS -> {
                // VehiclePass materials, GlassWaterPass images/buffers and ShadowPass.bind.
                for(int u=32;u<=38;u++)save(u,D2|SAMPLER);
                for(int u=52;u<=60;u++)save(u,u==56||u==60?BUFFER:D2);
                save(6,ARRAY);save(13,D2);save(14,D2);save(18,D3);save(21,ARRAY);save(22,D2);
                save(30,ARRAY);save(49,ARRAY);save(130,D2);
                for(int u=81;u<=83;u++)save(u,D2|SAMPLER);
            }
        }
        uboSlots=probe?PROBE_UBOS:phase==Phase.COLOUR||phase==Phase.SETUP?NATIVE_UBOS:SHADER_UBOS;
        ssboSlots=probe?PROBE_SSBOS:phase==Phase.GLASS?GLASS_SSBOS:MODEL_SSBOS;
        ubos=new int[uboSlots.length];uboRanges=new long[ubos.length*2];
        ssbos=new int[ssboSlots.length];ssboRanges=new long[ssbos.length*2];
        captureBuffers(uboSlots,ubos,uboRanges,GL31.GL_UNIFORM_BUFFER_BINDING,GL31.GL_UNIFORM_BUFFER_START,GL31.GL_UNIFORM_BUFFER_SIZE);
        captureBuffers(ssboSlots,ssbos,ssboRanges,GL43.GL_SHADER_STORAGE_BUFFER_BINDING,GL43.GL_SHADER_STORAGE_BUFFER_START,GL43.GL_SHADER_STORAGE_BUFFER_SIZE);
        int imageCount=switch(phase){case COLOUR,SETUP->4;case GLASS->3;case ECLIPSE->1;default->0;};
        images=new int[imageCount*6];
        for(int u=0;u<imageCount;u++)for(int k=0;k<6;k++)images[u*6+k]=GL30.glGetIntegeri(IMAGE_KEYS[k],u);
        CURRENT.set(this);
    }
    private void save(int unit,int mask){
        if(unit>=unitMask.length)return;
        int missing=mask&~unitMask[unit];
        for(int k=0;k<TARGETS.length;k++)if((missing&(1<<k))!=0)textures[unit*(TARGETS.length+1)+k]=GL30.glGetIntegeri(BINDINGS[k],unit);
        if((missing&SAMPLER)!=0)textures[unit*(TARGETS.length+1)+TARGETS.length]=GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,unit);
        unitMask[unit]|=mask;
    }
    static void skyInputs(IrisPipeline pipeline,IrisProgram program){
        Gfl2GlScope scope=CURRENT.get();
        if(scope==null)return;
        for(var entry:program.units.entrySet()){
            int unit=entry.getValue();
            if(unit==0)continue;
            String name=entry.getKey(),type=program.samplerTypes.getOrDefault(name,"");
            int mask=type.contains("3D")?D3:type.contains("1D")?D1:D2;
            var custom=pipeline.textures.custom("composite",name);
            if(custom!=null){
                mask|=D2;
                for(int k=0;k<TARGETS.length;k++)if(TARGETS[k]==custom.target())mask|=1<<k;
            }
            if(name.equals("uBandLight"))mask|=D3;
            if(name.equals("uCellLight"))mask|=ARRAY;
            scope.save(unit,mask|SAMPLER);
        }
    }
    static void materialUnit(int unit,int target){
        Gfl2GlScope scope=CURRENT.get();
        if(scope==null)return;
        for(int k=0;k<TARGETS.length;k++)if(TARGETS[k]==target){scope.save(unit,(1<<k)|SAMPLER);return;}
        throw new IllegalArgumentException("Unknown source texture target "+target);
    }
    private static void captureBuffers(int[] slots,int[] ids,long[] ranges,int name,int start,int size){
        for(int i=0;i<slots.length;i++){
            ids[i]=GL30.glGetIntegeri(name,slots[i]);
            if(ids[i]!=0){ranges[2*i]=GL32.glGetInteger64i(start,slots[i]);ranges[2*i+1]=GL32.glGetInteger64i(size,slots[i]);}
        }
    }
    private static void restoreBuffers(int target,int[] slots,int[] ids,long[] ranges){
        for(int i=0;i<slots.length;i++){
            if(ids[i]!=0&&ranges[i*2+1]>0)GL30.glBindBufferRange(target,slots[i],ids[i],ranges[i*2],ranges[i*2+1]);
            else GL30.glBindBufferBase(target,slots[i],ids[i]);
        }
    }
    @Override public void close(){
        CURRENT.set(enclosing);
        for(int u=0;u<unitMask.length;u++)if(unitMask[u]!=0){
            GL13.glActiveTexture(GL13.GL_TEXTURE0+u);
            for(int k=0;k<TARGETS.length;k++)if((unitMask[u]&(1<<k))!=0)GL11.glBindTexture(TARGETS[k],textures[u*(TARGETS.length+1)+k]);
            if((unitMask[u]&SAMPLER)!=0)GL33.glBindSampler(u,textures[u*(TARGETS.length+1)+TARGETS.length]);
        }
        restoreBuffers(GL31.GL_UNIFORM_BUFFER,uboSlots,ubos,uboRanges);
        restoreBuffers(GL43.GL_SHADER_STORAGE_BUFFER,ssboSlots,ssbos,ssboRanges);
        for(int u=0;u<images.length/6;u++){int i=u*6;GL42.glBindImageTexture(u,images[i],images[i+1],images[i+2]!=0,images[i+3],images[i+4],images[i+5]);}
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,arrayBuffer);GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER,uniformBuffer);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,storageBuffer);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,copyRead);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,copyWrite);GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,indirectBuffer);
        GL30.glBindVertexArray(vao);GL20.glUseProgram(program);GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,draw);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read);GL13.glActiveTexture(active);
        if(explicit==null){
            GL11.glPopAttrib();
            if(depthClamp)GL11.glEnable(GL32.GL_DEPTH_CLAMP);else GL11.glDisable(GL32.GL_DEPTH_CLAMP);
        } else explicit.restore();
        GL11.glReadBuffer(readBuffer);
        if(seamless)GL11.glEnable(GL32.GL_TEXTURE_CUBE_MAP_SEAMLESS);else GL11.glDisable(GL32.GL_TEXTURE_CUBE_MAP_SEAMLESS);
        if(audit!=null)audit.verify(phase.name());
    }
}
