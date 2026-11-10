package viewpoint.render;

import java.lang.reflect.*;
import java.util.*;
import org.json.JSONObject;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import viewpoint.iris.*;
import viewpoint.platform.Gl;
import viewpoint.platform.GlProgram;

/** Optional pack-owned character stages. Reuses the host's frozen ModelDraws. */
public final class AimohGF2Services {
    public static State sourceState(IrisPipeline p){return STATES.get(p.targets);}
    static boolean actorMotion(IrisPrograms programs){return BUNDLES.get(programs).actorMotion;}

    private static final Map<IrisPrograms,Bundle> BUNDLES=new IdentityHashMap<>();
    private static final Map<IrisTargets,State> STATES=new IdentityHashMap<>();
    private static final Method PASS,SCREEN;
    private static final Field BATCH_RANK;
    public static ModelBatches.Filter filter(ModelBatches.Filter original){
        IrisPipeline pipeline=AimohGf2SourceHost.pipeline;
        Bundle bundle=pipeline==null?null:BUNDLES.get(pipeline.programs);
        if(bundle==null||bundle.nativeEntityProgram!=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM))return original;
        State state=STATES.get(pipeline.targets);
        return state==null?original:index->original.take(index)&&!state.owns(index);
    }
    static {
        try{
            PASS=IrisPrograms.class.getDeclaredMethod("pass",ProgramSet.Program.class);PASS.setAccessible(true);
            SCREEN=IrisFrame.class.getDeclaredMethod("screen",IrisPipeline.class,IrisPrograms.Pass.class,String.class,FrameContext.class,boolean.class,Set.class);SCREEN.setAccessible(true);
            BATCH_RANK=ModelBatches.class.getDeclaredField("rank");BATCH_RANK.setAccessible(true);
        }catch(ReflectiveOperationException e){throw new ExceptionInInitializerError(e);}
    }
    public static Map<String,String> macros(Map<String,String> input){Map<String,String> result=new LinkedHashMap<>(input);result.put("VIEWPOINT_DANRP_HOST","1");result.put("VIEWPOINT_GF2_MODEL_SSBO","1");result.put("VIEWPOINT_DANRP_NATIVE_LIGHTS","1");return result;}
    private static boolean singleAlbedoProgram(String name){return name.equals("gbuffers_dan_trans")||name.equals("gbuffers_dan_trans_motion")||name.equals("gbuffers_dan_eyelash");}
    public static int firstUserUnit(String name,GlProgram program){return 96;}
    public static int nextUserUnit(String name,int next){
        // Keep the RC3 cloud-shadow, native-light, model-look and snow reservations.
        return next==130?131:next==139?160:next;
    }
    public static int checkedUserUnit(int unit,String program){
        if(AimohGf2AddonContext.compiling()){
            int limit=GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
            if(unit>=limit)throw new IllegalArgumentException("Eclipse shader "+program+" needs texture unit "+unit+"; driver exposes "+limit+" units");
        }
        return unit;
    }
    public static void bindReliefSamplers(IrisProgram program){
        if(!AimohGf2AddonContext.compiling())return;
        bindReliefSampler(program,"uRelief",Gl.UNIT_FAR_RELIEF);
        bindReliefSampler(program,"uReliefWater",Gl.UNIT_FAR_RELIEF_WATER);
        bindReliefSampler(program,"uReliefWalls",Gl.UNIT_FAR_RELIEF_WALLS);
        bindReliefSampler(program,"uReliefFlow",Gl.UNIT_FAR_RELIEF_FLOW);
        bindReliefSampler(program,"uMuzzleShadow",Gl.UNIT_MUZZLE_SHADOW);
    }
    private static void bindReliefSampler(IrisProgram program,String name,int unit){
        int id=program.gl.id(),location=GL20.glGetUniformLocation(id,name);
        if(location<0)return;
        // The host binds these textures on its reserved units, even for a shader with no declared sampler mapping.
        GL41.glProgramUniform1i(id,location,unit);
        program.units.remove(name);
        program.samplerTypes.remove(name);
    }
    private static RuntimeException failure(Throwable e){if(e instanceof InvocationTargetException&&e.getCause()!=null)e=e.getCause();return e instanceof RuntimeException?(RuntimeException)e:new IllegalStateException("DanRP character stage",e);}

    static final class Bundle {
        final int nativeEntityProgram;
        final boolean actorMotion;
        final AimohGf2ShadowReadback shadowReadback=new AimohGf2ShadowReadback();
        IrisPrograms.Pass skyBase,skyFilter;
        Bundle(IrisPrograms programs){
            nativeEntityProgram=programs.draws.get(IrisPrograms.Draw.ENTITIES).gl.id();
            actorMotion=samplesActorMotion(programs);
        }
        void delete(){
            shadowReadback.close();
            if(skyBase!=null&&skyBase.program()!=null)skyBase.program().delete();
            if(skyFilter!=null&&skyFilter.program()!=null)skyFilter.program().delete();
        }
    }
    private static boolean samplesActorMotion(IrisPrograms programs){
        if(programs.draws.values().stream().anyMatch(AimohGF2Services::samplesActorMotion))return true;
        for(var passes:programs.passes.values())for(var pass:passes){
            if(samplesActorMotion(pass.program())||pass.computes().stream().anyMatch(AimohGF2Services::samplesActorMotion))return true;
        }
        return false;
    }
    private static boolean samplesActorMotion(IrisProgram program){return program!=null&&program.units.containsKey("vpDanMotionTex");}
    public static void link(IrisPrograms programs,IrisPack pack,IrisPack.Configured configured,Map<String,String> macros){
        if(!AimohGf2AddonContext.supports(pack))return;
        Bundle b=null;
        try {
            if(pack.source.exists("gf2/manifest.json")&&!"false".equalsIgnoreCase(configured.values().get("VP_DANRP_PIPELINE"))){
                String problem=AimohGf2Device.unsupported();
                if("true".equalsIgnoreCase(configured.values().get("PLANET_CURVATURE")))problem="Planet Curvature requires a separate character shadow projection";
                if(problem!=null){System.err.println("[R116 Eclipse] Character rendering unavailable: "+problem);return;}
                try{AimohGf2Runtime.link(programs,pack);}
                catch(java.io.IOException|IllegalArgumentException|org.json.JSONException e){
                    System.err.println("[R116 Eclipse] Character rendering unavailable; check the R116 character MOD and restart or reload the shader: "+e);
                    return;
                }
                b=new Bundle(programs);
                b.skyBase=(IrisPrograms.Pass)PASS.invoke(programs,new ProgramSet.Program("dan_sky_base","danrp/sky_base.vsh",null,"danrp/sky_base.fsh",List.of()));
                b.skyFilter=(IrisPrograms.Pass)PASS.invoke(programs,new ProgramSet.Program("dan_sky_filter","danrp/sky_filter.vsh",null,"danrp/sky_filter.fsh",List.of()));
                if(b.skyBase.program()==null||b.skyFilter.program()==null)throw new IllegalStateException("Native filtered sky stages missing");BUNDLES.put(programs,b);return;
            }
        } catch(Throwable e){if(b!=null)b.delete();AimohGf2Runtime.releasePrograms(programs);programs.delete();throw failure(e);}
    }
    public static void releasePrograms(IrisPrograms programs){AimohGf2Runtime.releasePrograms(programs);Bundle b=BUNDLES.remove(programs);if(b!=null)b.delete();}
    public static void release(IrisPipeline pipeline){AimohGf2Runtime.release(pipeline);State s=STATES.remove(pipeline.targets);if(s!=null)s.delete();}
    public static void beginFrame(IrisPipeline pipeline,FrameContext frame){
        if(BUNDLES.containsKey(pipeline.programs)&&frame.scene.cameraCut)pipeline.uniforms.reset();
    }
    public static long extraBytes(IrisPipeline pipeline){State s=STATES.get(pipeline.targets);return s==null?0L:s.bytes;}
    public static int texture(IrisTargets targets,String name){
        if(name.equals("uCellLight")||name.equals("uCellTable")||name.equals("uLampFields")||name.equals("uMuzzleShadow"))return 0; // Bound by the native service after the generic binder.
        State s=STATES.get(targets);if(s!=null)return s.texture(name);
        return name.startsWith("vpDan")?0:-1;
    }
    /** Native spatial lighting, using already updated house resources. */
    public static void nativeLights(IrisTargets targets,IrisProgram entry){
        Integer band=entry.units.get("uBandLight");
        if(band!=null){MeshArena.bindLight(5,entry.gl);BandLight.bind(band);}
        Integer cell=entry.units.get("uCellLight"),table=entry.units.get("uCellTable");
        if(cell!=null&&table!=null)CellLightPages.bind(cell,table);
    }
    static void beforeSourceColor(IrisFrame host,IrisPipeline p,FrameContext frame,Set<Integer> flips){
        State state=STATES.get(p.targets);
        if(state==null||state.actors.isEmpty())return;
        try{
            SCREEN.invoke(host,p,state.bundle.skyBase,"composite",frame,false,flips);
            SCREEN.invoke(host,p,state.bundle.skyFilter,"composite",frame,false,flips);
        }catch(ReflectiveOperationException e){throw failure(e);}
    }

    /** One bound material per transparent draw replaces 16 native samplers only in these stages. */
    public static String fragmentSource(String source,String name){
        boolean transparent=name.equals("gbuffers_dan_trans")||name.equals("gbuffers_dan_trans_motion");
        boolean caster=name.equals("gbuffers_dan_actor_shadow");
        boolean sourceHelper=name.equals("gbuffers_dan_fringe")||name.equals("gbuffers_dan_eye_blend")||name.equals("gbuffers_dan_eyelash");
        boolean eclipseCoverage=source.contains("bool danEclipseOwnCoverage(");
        if(!transparent&&!caster&&!sourceHelper&&!eclipseCoverage)return source;
        String cut="if (vp_vGlass == 1 ? a < uIrisGlassRange.y : a < 0.01 || a < 0.99 && a <= vp_fadeNoise()) discard;";
        if(source.indexOf(cut)<0||source.indexOf(cut)!=source.lastIndexOf(cut))throw new IllegalStateException("DanRP native alpha prologue changed");
        if(eclipseCoverage){String opacity=singleAlbedoProgram(name)?"if(danEclipseOpacity()<0.01)discard;":"float visible=danEclipseOpacity();if(visible<0.01||visible<0.99&&visible<=vp_fadeNoise())discard;";source=source.replace("void vp_cutout() {","bool danEclipseOwnCoverage();\nfloat danEclipseOpacity();\nvoid vp_cutout() {").replace(cut,"if(danEclipseOwnCoverage()){ "+opacity+" }else{ "+cut+" }");if(!singleAlbedoProgram(name))return source;}
        if(caster&&!eclipseCoverage){source=source.replace("void vp_cutout() {","bool danTransMaterialHere();\nvoid vp_cutout() {").replace(cut,"if(!danTransMaterialHere()){ "+cut+" }");return source;}
        source=source.replace(cut,"");
        if(sourceHelper&&!singleAlbedoProgram(name))return source;
        String declaration="uniform sampler2D vpDanTransAlbedo;";
        if(source.indexOf(declaration)<0||source.indexOf(declaration)!=source.lastIndexOf(declaration))throw new IllegalStateException("DanRP transparent sampler declaration changed");
        source=source.replace(declaration,"");
        String marker="vec4 vp_modelTexel(vec2 uv) {";int start=source.indexOf(marker),open=start<0?-1:source.indexOf('{',start),end=open,depth=0;
        if(open<0)throw new IllegalStateException("DanRP native model sampling function missing");
        for(;end<source.length();end++){char c=source.charAt(end);if(c=='{')depth++;else if(c=='}'&&--depth==0){end++;break;}}
        if(depth!=0||end>source.length())throw new IllegalStateException("DanRP native model sampling function malformed");
        return source.substring(0,start)+declaration+"\nvec4 vp_modelTexel(vec2 uv){return texture(vpDanTransAlbedo,uv);}\n"+source.substring(end);
    }

    static final class Actor {
        final int id;
        final JSONObject model;
        final float[] placement;
        final Vector3f center;
        final float radius;
        final List<AimohGf2Runtime.Draw> draws=new ArrayList<>();
        Actor(int id,JSONObject model,ModelDraws draws,int index,FrameContext frame){
            this.id=id;
            this.model=model;
            int base=index*68;
            placement=Arrays.copyOfRange(draws.values,base,base+16);
            center=new Vector3f(frame.eyeX-draws.values[base+24],draws.values[base+25]-frame.eyeY,frame.eyeZ-draws.values[base+26]);
            radius=Math.max(.05f,draws.values[base+27]*1.08f);
            if(!center.isFinite()||!Float.isFinite(radius))throw new IllegalStateException("Eclipse actor bounds are not finite");
        }
    }
    static final class State {
        private static final java.util.regex.Pattern PART=java.util.regex.Pattern.compile("aimoh:pmx-part-gf2-(-?\\d+)-(\\d+)");
        final Bundle bundle;
        final Map<Integer,Actor> actors=new LinkedHashMap<>();
        private boolean[] owned=new boolean[0];
        int width,height,motion,shadow,shadowFbo,atlasSize;
        long bytes;

        State(Bundle b){bundle=b;}
        int texture(String name){return name.equals("vpDanMotionTex")?motion:-1;}
        boolean owns(int index){return index>=0&&index<owned.length&&owned[index];}
        void delete(){
            if(shadowFbo!=0)GL30.glDeleteFramebuffers(shadowFbo);
            if(shadow!=0)GL11.glDeleteTextures(shadow);
            if(motion!=0)GL11.glDeleteTextures(motion);
            motion=shadow=shadowFbo=0;
            bytes=0;
        }
        private static int texture(int width,int height,int format){
            int texture=GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            GL45.glTextureStorage2D(texture,1,format,width,height);
            GL45.glTextureParameteri(texture,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
            GL45.glTextureParameteri(texture,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            GL45.glTextureParameteri(texture,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
            GL45.glTextureParameteri(texture,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
            return texture;
        }
        void ensure(IrisPipeline p,FrameContext frame){
            int atlas=actors.isEmpty()?1:2048;
            int w=0,h=0;
            if(bundle.actorMotion){w=actors.isEmpty()?1:frame.width;h=actors.isEmpty()?1:frame.height;}
            if(w==width&&h==height&&atlas==atlasSize&&shadowFbo!=0)return;
            delete();
            width=w;height=h;atlasSize=atlas;
            try{
                if(bundle.actorMotion)motion=texture(w,h,GL30.GL_RG16F);
                shadow=texture(atlas,atlas,GL14.GL_DEPTH_COMPONENT16);
                shadowFbo=GL45.glCreateFramebuffers();
                GL45.glNamedFramebufferTexture(shadowFbo,GL30.GL_DEPTH_ATTACHMENT,shadow,0);
                GL45.glNamedFramebufferDrawBuffer(shadowFbo,GL11.GL_NONE);
                GL45.glNamedFramebufferReadBuffer(shadowFbo,GL11.GL_NONE);
                if(GL45.glCheckNamedFramebufferStatus(shadowFbo,GL30.GL_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)
                    throw new IllegalStateException("Eclipse actor shadow framebuffer incomplete");
                bytes=(long)w*h*4L+(long)atlas*atlas*2L;
                p.checkPool();
            }catch(RuntimeException e){delete();throw e;}
        }
        void collect(ModelPass models,FrameContext frame,AimohGf2PackData data){
            ModelDraws draws=AimohWorkshopRender.draws(models);
            final int[] rank;
            try{rank=(int[])BATCH_RANK.get(models.batches);}
            catch(IllegalAccessException e){throw new IllegalStateException("Eclipse draw ranks unavailable",e);}
            actors.clear();
            if(owned.length<draws.count)owned=new boolean[draws.count];else Arrays.fill(owned,false);
            Map<Integer,JSONObject> palettes=new HashMap<>();
            for(int i=0;i<draws.count;i++){
                if(rank[i]<0||(draws.flags[i]&ModelDraws.COLOUR)==0||draws.meshes[i]==null||draws.meshes[i].getPath()==null)continue;
                var part=PART.matcher(draws.meshes[i].getPath().getPath());
                if(!part.matches())continue;
                int palette=draws.palette[i];
                JSONObject model=palettes.computeIfAbsent(palette,key->data.modelForPalette(draws,key));
                if(model==null)continue;
                int material=Integer.parseInt(part.group(1)),ordinal=Integer.parseInt(part.group(2));
                JSONObject descriptor=model.getJSONArray("parts").optJSONObject(ordinal);
                if(descriptor==null||descriptor.getInt("source_material")!=material)continue;
                int id=(int)draws.palettes[palette*12+24];
                Actor actor=actors.get(id);
                if(actor==null){actor=new Actor(id,model,draws,i,frame);actors.put(id,actor);}
                actor.draws.add(new AimohGf2Runtime.Draw(i,material,ordinal));
                owned[i]=true;
            }
        }
    }

    private static void eclipseDirection(IrisPipeline p,Map<String,float[]> values,boolean hasActors){
        boolean day=values.get("sunElevation")[0]>1e-5f;
        Vector3f direction;
        if(!day&&"true".equals(p.configured.values().get("CUSTOM_MOON_ROTATION"))){
            float[] vector=AimohGf2SourceHost.nativeMoonDirection(hasActors);
            direction=new Vector3f(vector[0],vector[1],vector[2]);
        }else{
            String smooth=day?"SMOOTH_SUN_ROTATION":"SMOOTH_MOON_ROTATION";
            float[] vector=values.get("true".equals(p.configured.values().get(smooth))?(day?"unsigned_WsunVecSmooth":"unsigned_WmoonVecSmooth"):(day?"sunPosition":"moonPosition"));
            if(vector==null||vector.length<3)throw new IllegalStateException("Eclipse native light direction input missing: "+smooth);
            direction=new Vector3f(vector[0],vector[1],vector[2]);
            if(!"true".equals(p.configured.values().get(smooth)))new Matrix4f().set(values.get("gbufferModelViewInverse")).transformDirection(direction);
            if(!day){
                float[] sv=values.get("sunPosition");Vector3f sun=new Vector3f(sv[0],sv[1],sv[2]);new Matrix4f().set(values.get("gbufferModelViewInverse")).transformDirection(sun);
                if(direction.dot(sun.negate().normalize())<.9999f)direction.negate();
            }
        }
        if(!direction.isFinite()||direction.lengthSquared()<1e-12f)throw new IllegalStateException("Invalid Eclipse active light direction");
        direction.normalize();values.put("lightDir",new float[]{direction.x,direction.y,direction.z});
    }
    public static void prepare(IrisPipeline p,FrameContext frame,ShadowPass shadow,ModelPass models,Map<String,float[]> values){
        Bundle bundle=BUNDLES.get(p.programs);if(bundle==null)return;
        AimohGf2SourceHost.prepare(p,frame,shadow,models,values);
        bundle.shadowReadback.reset();
        State state=STATES.computeIfAbsent(p.targets,key->new State(bundle));
        state.collect(models,frame,AimohGf2Runtime.PACKS.get(p.programs).data);
        boolean hasActors=!state.actors.isEmpty();
        eclipseDirection(p,values,hasActors);
        state.ensure(p,frame);
        if(hasActors&&values.get("sunElevation")[0]>1e-5f&&"true".equals(p.configured.values().get("CUSTOM_MOON_ROTATION"))){
            int[] matrix=AimohGf2SourceHost.gpuField("customShadowMatrixSSBO");
            bundle.shadowReadback.queue(matrix[0],matrix[1]);
        }
        SceneData scene=frame.scene;
        values.put("uSceneOrigin",new float[]{(float)scene.originX,(float)scene.originY,(float)scene.originZ});
        values.put("uDaylight",new float[]{scene.daylightKnown?scene.daylightR:.8f,scene.daylightKnown?scene.daylightG:.8f,scene.daylightKnown?scene.daylightB:.8f});
    }
}
