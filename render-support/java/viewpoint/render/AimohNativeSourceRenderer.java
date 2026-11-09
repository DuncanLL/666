package viewpoint.render;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.regex.*;
import org.joml.Matrix3d;
import org.joml.Matrix3f;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.json.*;
import org.lwjgl.opengl.*;
import viewpoint.platform.GlProgram;
import viewpoint.platform.IrisPacks;
import viewpoint.platform.AimohNativePackSelection;

/** Original GF2 source stages in Native AFTER_OPAQUE; Native world/lighting/post are retained. */
public final class AimohNativeSourceRenderer implements AutoCloseable {
    private final AimohGf2PackData data;
    private final AimohGf2PackMaterials materials;
    private final AimohNativeSourcePrograms nativePrograms;
    private final AimohGf2DepthStencil target;
    private final AimohNativeGf2Environment environment;
    private final GlProgram motionBridge;
    private final AimohNativeDisplayTransfer display;
    private final Map<String,AimohGf2FrameInputs> builders=new HashMap<>();
    private final Map<BankKey,AimohGf2RegisterBank> banks=new HashMap<>();
    private final Map<BankKey,String> bankScopes=new HashMap<>();
    private record RegisterLayout(JSONObject source,Integer shOffset,Integer mainOffset){}
    private final Map<String,RegisterLayout> registerLayouts=new HashMap<>();
    private final Map<JSONObject,Integer> queues=new IdentityHashMap<>();
    private final Map<Integer,History> histories=new HashMap<>();
    private final Map<RootKey,RootPose> rootPoses=new HashMap<>();
    private final Map<Integer,Integer> actorLighting=new HashMap<>();
    private final int[] singleDraw=new int[1];
    private static final String[] RELIEF_NAMES={"uReliefGrid","uReliefCamera","uReliefScale","uReliefSplit","uReliefFlowCells"};
    private final int[] reliefLocations=new int[RELIEF_NAMES.length];
    private final float[][] reliefValues=new float[RELIEF_NAMES.length][4];
    private int reliefBaseId;
    private final List<String> stageOrder=new ArrayList<>();
    private Map<String,float[]> previousValues;
    private long revision;
    private int colour,width,height,frames,lastActors,lastDraws;
    private long lastFrame=-1;
    private int nativeLightCount,unsupportedCarBeams;
    private float[] actorEngineLight;
    private AimohNativeLocalLights localLights;
    private JSONObject geometryWitness;
    private int earlyFrames,earlyDraws;
    private long earlyFrame=-1;
    private int shadowPhase,shadowDraws,shadowViews;
    private long shadowFrame=-1;
    private int casterQuery,visibleCasterViews;
    private boolean casterQueryActive;
    private boolean reportedPaletteFallback;
    private final Map<String,Integer> stageDraws=new LinkedHashMap<>();
    private FrameActors frozenActors;
    private static final Pattern PART=Pattern.compile("aimoh:pmx-part-gf2-(-?\\d+)-(\\d+)");
    private record Draw(int index,int material,int ordinal){}
    private record Actor(int palette,JSONObject model,List<Draw> draws){}
    private record ActorParts(JSONObject model,List<Draw> colour,List<Draw> shadow){}
    private record FrameActors(long frame,List<Actor> colour,List<Actor> shadow,Set<Integer> submitted){}
    private record Casters(Actor actor,List<Draw> draws){}
    private record History(String asset,float[] placement,float[] eye,int frame,float jitterU,float jitterV){}
    private record RootPose(int frame,double[] rotation,double[] previous){}
    private record RootKey(int actor,String root){}
    private record BankKey(int actor,String shader,int binding,String root){}
    private record Local(Map<String,float[]> values,float[] inverse,float[] inverseNormal,float[] previousInverse){}
    private record Ready(Actor actor,int identity,History old,boolean history,float[] placement,float[] eye,
                         AimohGf2FrameInputs builder,AimohGf2FrameInputs.Inputs packet,Map<String,float[]> input,
                         AimohNativeLocalLights lights,AimohNativeGf2Environment.Shadow shadow,int attributes,
                         int cube,int lighting,Map<String,Local> locals){}
    private record Job(Ready ready,Draw draw,int queue,float depth){}
    private static final List<String> STAGES=List.of("GFCharForward","GFHairShadow","GFCharFaceShadow","GFCharHairTransE","GFOutline","GFCharTransMrtWrtie","GFCharTransBack","GFCharTransFront","GFCharTransOutline");
    public AimohNativeSourceRenderer()throws Exception{
        this(new AimohGf2PackData((viewpoint.iris.SourceFiles)AimohNativePackSelection::readData));
    }
    AimohNativeSourceRenderer(AimohGf2PackData prepared)throws Exception{
        data=prepared;
        data.manifest.getJSONObject("features").put("authored_eye_add_exposure_adapter",false);
        var caps=GL.getCapabilities();
        AimohGf2PackMaterials createdMaterials=null;AimohNativeSourcePrograms createdPrograms=null;AimohGf2DepthStencil createdTarget=null;AimohNativeGf2Environment createdEnvironment=null;GlProgram createdMotion=null;AimohNativeDisplayTransfer createdDisplay=null;
        try{createdTarget=new AimohGf2DepthStencil();createdMaterials=new AimohGf2PackMaterials(data);createdPrograms=new AimohNativeSourcePrograms(data,createdMaterials);createdEnvironment=new AimohNativeGf2Environment();createdMotion=AimohNativePackSelection.compute("Original source MRT to Native velocity","gf2/native_motion.comp");createdMotion.sampler("sourceNativeMotion",0);}
        catch(Exception e){if(createdMotion!=null)createdMotion.delete();if(createdEnvironment!=null)createdEnvironment.close();if(createdPrograms!=null)createdPrograms.close();if(createdMaterials!=null)createdMaterials.close();if(createdTarget!=null){AimohNativeSourceScope.forget((GlProgram)AimohNativeGf2Environment.field(createdTarget,"copy"));createdTarget.close();}throw e;}
        try{if(data.manifest.has("original_display_profile"))createdDisplay=new AimohNativeDisplayTransfer(data.manifest.getJSONObject("original_display_profile"));}
        catch(Exception e){System.err.println("[R116 Native] original display profile unavailable; retaining Native colour response: "+e.getMessage());}
        target=createdTarget;materials=createdMaterials;nativePrograms=createdPrograms;environment=createdEnvironment;motionBridge=createdMotion;display=createdDisplay;
        System.out.println("[R116 Native] GPU="+GL11.glGetString(GL11.GL_RENDERER)+"; driver="+GL11.glGetString(GL11.GL_VERSION)+"; maxTexture="+GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE)+"; maxUBO="+GL11.glGetInteger(GL31.GL_MAX_UNIFORM_BLOCK_SIZE)+"; textureUnits="+GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS)+"; BC7="+(caps.OpenGL42||caps.GL_ARB_texture_compression_bptc)+"; S3TC="+caps.GL_EXT_texture_compression_s3tc+"; characterData="+data.identity);
        AimohNativeGf2Registry.attach(data);
        AimohNativeGf2Registry.beforeLighting(frame->{try{prepass(frame);}catch(Exception e){throw new IllegalStateException("Native source depth/GBuffer",e);}});
        AimohNativeGf2Registry.shadows((matrix,candidates,count,filter)->{try{cast(matrix,candidates,count,filter);}catch(Exception e){throw new IllegalStateException("Native original ShadowCaster",e);}});
    }
    private static Matrix4d matrix(float[] a){return new Matrix4d(new Matrix4f().set(a));}
    public void diagnostics(){if(casterQuery==0)casterQuery=GL15.glGenQueries();}
    private static float[] flat(Matrix4d a){return new Matrix4f(a).get(new float[16]);}
    private static float[] normal(Matrix4d a){return new Matrix3f(new Matrix4f(a)).transpose().get(new float[9]);}
    private static final Pattern QUEUE=Pattern.compile("([A-Za-z]+)([+-]\\d+)?");
    private static int queue(JSONObject material,JSONObject pass){
        int override=material.getInt("render_queue_override");if(override>=0)return override;String text="Geometry";
        for(Object value:pass.getJSONObject("state").getJSONObject("m_Tags").getJSONArray("tags")){JSONArray tag=(JSONArray)value;if(tag.getString(0).equals("QUEUE"))text=tag.getString(1);}
        Matcher m=QUEUE.matcher(text);if(!m.matches())throw new IllegalArgumentException("Source render queue differs");
        int base=switch(m.group(1)){case "Background"->1000;case "Geometry"->2000;case "AlphaTest"->2450;case "Transparent"->3000;case "Overlay"->4000;default->throw new IllegalArgumentException("Unknown source queue");};return base+(m.group(2)==null?0:Integer.parseInt(m.group(2)));
    }
    private FrameActors actors(FrameContext frame,ModelPass models)throws Exception{
        if(frozenActors!=null&&frozenActors.frame==frame.index)return frozenActors;
        ModelDraws draws=models.draws();
        int[] rank=(int[])AimohNativeGf2Environment.field(models.batches,"rank");
        Map<Integer,ActorParts> found=new LinkedHashMap<>();
        List<Actor> colour=new ArrayList<>(),shadow=new ArrayList<>();
        Set<Integer> submitted=new HashSet<>();
        // Stock batches omit meshes/textures still loading. Use the same eligible draw set.
        for(int i=0;i<draws.count;i++){
            if(rank[i]<0)continue;
            int flags=draws.flags[i];
            if(flags==0||draws.meshes[i]==null||draws.meshes[i].getPath()==null)continue;
            Matcher match=PART.matcher(draws.meshes[i].getPath().getPath());if(!match.matches())continue;
            int palette=draws.palette[i];
            ActorParts actor=found.get(palette);
            if(actor==null){
                JSONObject model=data.modelForPalette(draws,palette);
                if(model==null){
                    if(!reportedPaletteFallback){
                        System.out.println("[R116 Native] Source mesh has no compatible frozen pose palette; using Viewpoint model pass until a matching pose arrives.");
                        reportedPaletteFallback=true;
                    }
                    continue;
                }
                actor=new ActorParts(model,new ArrayList<>(),new ArrayList<>());found.put(palette,actor);
                submitted.add((int)draws.palettes[palette*12+24]);
            }
            int material=Integer.parseInt(match.group(1)),ordinal=Integer.parseInt(match.group(2));
            if(actor.model.getJSONArray("parts").getJSONObject(ordinal).getInt("source_material")!=material)throw new IllegalArgumentException("Native source material/part mapping differs");
            Draw draw=new Draw(i,material,ordinal);
            if((flags&ModelDraws.COLOUR)!=0){
                if(actor.colour.isEmpty())colour.add(new Actor(palette,actor.model,actor.colour));
                actor.colour.add(draw);
            }
            if((flags&ModelDraws.SHADOW)!=0){
                if(actor.shadow.isEmpty())shadow.add(new Actor(palette,actor.model,actor.shadow));
                actor.shadow.add(draw);
            }
        }
        return frozenActors=new FrameActors(frame.index,colour,shadow,submitted);
    }
    private void ensure(int w,int h){
        if(width==w&&height==h)return;if(colour!=0)GL11.glDeleteTextures(colour);width=w;height=h;target.ensure(w,h);target.ensureOriginalOutputs(true);
        colour=GL45.glCreateTextures(GL11.GL_TEXTURE_2D);GL45.glTextureStorage2D(colour,1,GL30.GL_RGBA16F,w,h);GL45.glTextureParameteri(colour,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL45.glTextureParameteri(colour,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
    }
    private static boolean writesDepth(JSONObject pass){Object value=pass.getJSONObject("state").get("zWrite");return value instanceof Number n?n.doubleValue()!=0:((JSONObject)value).getDouble("value")!=0;}
    private static AutoCloseable modelBindings(ModelPass models){
        models.begin();
        return models::end;
    }
    private void cast(Matrix4f nativeVp,int[] candidates,int count,ModelBatches.Filter filter)throws Exception{
        FrameContext frame=(FrameContext)AimohNativeGf2Environment.world("frame");if(shadowFrame!=frame.index){shadowFrame=frame.index;shadowDraws=shadowViews=visibleCasterViews=0;}shadowPhase++;
        ModelPass models=(ModelPass)AimohNativeGf2Environment.world("models");ModelDraws draws=models.draws();List<Actor> actors=actors(frame,models).shadow;if(actors.isEmpty())return;
        boolean[] selected=null;if(candidates!=null){selected=new boolean[draws.count];for(int i=0;i<count;i++)selected[candidates[i]]=true;}
        List<Casters> casters=new ArrayList<>();
        for(Actor actor:actors){
            List<Draw> eligible=new ArrayList<>();
            for(Draw draw:actor.draws)if((selected==null||selected[draw.index])&&filter.take(draw.index)&&materials.optionalPass(draw.material,"ShadowCaster")!=null)eligible.add(draw);
            if(!eligible.isEmpty())casters.add(new Casters(actor,eligible));
        }
        if(casters.isEmpty())return;
        try(AimohGf2GlScope scope=new AimohGf2GlScope();AutoCloseable bindings=modelBindings(models)){
            captureRelief(models);
            float factor=GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL)?GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR):0,units=GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL)?GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS):0;
            float[] eye={frame.eyeX,frame.eyeY,frame.eyeZ};Matrix4f sourceVp=new Matrix4f(nativeVp).translate(eye[0],eye[1],eye[2]).scale(-1,1,-1);
            org.joml.Vector3f right=new org.joml.Vector3f(sourceVp.m00(),sourceVp.m10(),sourceVp.m20()).normalize(),up=new org.joml.Vector3f(sourceVp.m01(),sourceVp.m11(),sourceVp.m21()).normalize();
            if(java.lang.Math.abs(right.dot(up))>1e-4)throw new IllegalArgumentException("Native shadow projection has unsupported skew");org.joml.Vector3f back=new org.joml.Vector3f(right).cross(up).normalize();
            Matrix4f view=new Matrix4f(right.x,up.x,back.x,0,right.y,up.y,back.y,0,right.z,up.z,back.z,0,0,0,0,1),inverse=new Matrix4f(view).invert();
            Map<String,float[]> nativeValues=AimohNativeSourceSpace.values(frame,new float[]{frame.scene.sunX,frame.scene.sunY,frame.scene.sunZ},null);
            if(casterQuery!=0){GL15.glBeginQuery(GL33.GL_ANY_SAMPLES_PASSED,casterQuery);casterQueryActive=true;}
            for(Casters caster:casters){
                Actor actor=caster.actor;
                int first=actor.draws.get(0).index,identity=(int)draws.palettes[actor.palette*12+24];float[] placement=Arrays.copyOfRange(draws.values,first*68,first*68+16);
                AimohGf2FrameInputs builder=builders.computeIfAbsent(actor.model.getString("pmx_sha256"),id->new AimohGf2FrameInputs(actor.model,data.manifest.getJSONObject("original_camera_fill")));
                Map<String,float[]> input=new LinkedHashMap<>(builder.build(nativeValues,placement,eye,placement,eye,frame.width,frame.height,frame.time,false,new float[0],new float[0],new float[0]).values());
                input.put("unity_MatrixVP",sourceVp.get(new float[16]));input.put("unity_MatrixV",view.get(new float[16]));input.put("unity_MatrixInvV",inverse.get(new float[16]));input.put("unity_CameraToWorld",inverse.get(new float[16]));input.put("glstate_matrix_projection",new Matrix4f(sourceVp).mul(inverse).get(new float[16]));
                int attributes=materials.attributeTexture(actor.model),nativeShadow=((int[])AimohNativeGf2Environment.field(AimohNativeGf2Environment.world("shadows"),"views"))[0];Map<String,Local> locals=new HashMap<>();
                for(Draw draw:caster.draws){
                    String root=actor.model.getJSONObject("renderer_frames").getJSONObject("materials").optString(Integer.toString(draw.material),null);Local local=root==null?new Local(input,input.get("unity_WorldToObject"),normal(matrix(input.get("unity_ObjectToWorld"))),input.get("unity_WorldToObject")):locals.computeIfAbsent(root,r->local(builder,r,draws,draw,input,false,identity));
                    AimohGf2OriginalProgram program=nativePrograms.source(draw.material,"ShadowCaster");program.registers(registers(program,draw.material,root,local.values,identity,true));program.textures(materials.textureBindings(program,draw.material,environment.earlyReflection(),nativeShadow,environment.shadowSampler));
                    program.nativeInputs(local.inverse,local.inverseNormal,actor.model.getInt("attribute_records"));program.nativeFrame(placement,local.inverse,eye,new float[2],false);GL13.glActiveTexture(GL13.GL_TEXTURE0+126);GL11.glBindTexture(GL11.GL_TEXTURE_2D,attributes);
                    JSONObject state=materials.state(draw.material,"ShadowCaster",false).put("offsetFactor",factor).put("offsetUnits",units);GL11.glDrawBuffer(GL11.GL_NONE);if(drawNative(frame,models,program,state,draw.index)!=1)throw new IllegalStateException("Native source caster was not submitted");shadowDraws++;
                }
            }shadowViews++;
            if(casterQueryActive){GL15.glEndQuery(GL33.GL_ANY_SAMPLES_PASSED);casterQueryActive=false;visibleCasterViews+=GL15.glGetQueryObjecti(casterQuery,GL15.GL_QUERY_RESULT);}
        }finally{if(casterQueryActive){GL15.glEndQuery(GL33.GL_ANY_SAMPLES_PASSED);casterQueryActive=false;}}
    }
    private void prepass(FrameContext frame)throws Exception{
        if(earlyFrame==frame.index)throw new IllegalStateException("Duplicate source GBuffer callback");earlyFrame=frame.index;earlyDraws=0;
        ModelPass models=(ModelPass)AimohNativeGf2Environment.world("models");ModelDraws draws=models.draws();List<Actor> actors=actors(frame,models).colour;if(actors.isEmpty())return;
        Targets nativeTargets=(Targets)AimohNativeGf2Environment.world("targets");
        try(AimohGf2GlScope scope=new AimohGf2GlScope();AutoCloseable bindings=modelBindings(models)){
            captureRelief(models);
            environment.prepareDepth(frame);ensure(frame.width,frame.height);target.targets(Map.of(0,nativeTargets.albedo,1,nativeTargets.normal,2,nativeTargets.engineLight,3,nativeTargets.velocity));target.begin(nativeTargets.depth);
            Map<String,float[]> values=AimohNativeSourceSpace.values(frame,environment.direction,previousValues);
            for(int actorIndex=0;actorIndex<actors.size();actorIndex++){
                Actor actor=actors.get(actorIndex);if(actorIndex>0)target.clearActorStencil();int first=actor.draws.get(0).index,identity=(int)draws.palettes[actor.palette*12+24];float[] placement=Arrays.copyOfRange(draws.values,first*68,first*68+16),eye={frame.eyeX,frame.eyeY,frame.eyeZ};
                History old=histories.get(identity);boolean history=old!=null&&old.asset.equals(actor.model.getString("pmx_sha256"))&&old.frame==frames-1&&!frame.scene.cameraCut;
                AimohGf2FrameInputs builder=builders.computeIfAbsent(actor.model.getString("pmx_sha256"),id->new AimohGf2FrameInputs(actor.model,data.manifest.getJSONObject("original_camera_fill")));
                var packet=builder.build(values,placement,eye,history?old.placement:placement,history?old.eye:eye,width,height,frame.time,history,new float[0],new float[0],new float[0]);
                Map<String,float[]> input=new LinkedHashMap<>(packet.values());var shadow=environment.shadow(Arrays.copyOfRange(draws.values,first*68+24,first*68+27),eye);
                input.put("_MainLightWorldToShadow",shadow.sourceMatrices());input.put("_MainLightShadowParams",new float[]{shadow.enabled()?1:0,1,0,0});input.put("_MainLightShadowmapSize",new float[]{1f/shadow.size(),1f/shadow.size(),shadow.size(),shadow.size()});input.put("_CascadeDepthBiasArray",new float[4]);input.put("_CascadeNormalBiasArray",new float[4]);
                Map<String,Local> locals=new HashMap<>();int attributes=materials.attributeTexture(actor.model);
                for(Draw draw:actor.draws){JSONObject pass=materials.optionalPass(draw.material,"GFCharForward");if(pass==null||!writesDepth(pass))continue;
                    String root=actor.model.getJSONObject("renderer_frames").getJSONObject("materials").optString(Integer.toString(draw.material),null);
                    Local local=root==null?new Local(input,input.get("unity_WorldToObject"),normal(matrix(input.get("unity_ObjectToWorld"))),packet.previousInverse()):locals.computeIfAbsent(root,r->local(builder,r,draws,draw,input,history,identity));
                    AimohGf2OriginalProgram program=nativePrograms.early(draw.material);characterControls(program,draw.material);program.registers(registers(program,draw.material,root,local.values,identity,true));program.textures(materials.textureBindings(program,draw.material,environment.earlyReflection(),shadow.texture(),environment.shadowSampler));
                    program.nativeInputs(local.inverse,local.inverseNormal,actor.model.getInt("attribute_records"));program.nativeFrame(history?old.placement:placement,local.previousInverse,history?old.eye:eye,new float[2],history);
                    GlProgram gl=program.nativeProgram();gl.use();gl.set("gf2NativePlainViewProjection",frame.plainViewProjection);gl.set("gf2NativePreviousViewProjection",history?frame.previousViewProjection:frame.plainViewProjection);gl.set("gf2NativePreviousSourceToWorld",new Matrix4f().set(local.values.get("unity_MatrixPreviousM")));
                    GL13.glActiveTexture(GL13.GL_TEXTURE0+126);GL11.glBindTexture(GL11.GL_TEXTURE_2D,attributes);GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0,GL30.GL_COLOR_ATTACHMENT1,GL30.GL_COLOR_ATTACHMENT2,GL30.GL_COLOR_ATTACHMENT3});
                    int issued=drawNative(frame,models,program,materials.state(draw.material,"GFCharForward",true),draw.index);if(issued!=1)throw new IllegalStateException("Native early source job absent");earlyDraws++;
                }
            }
            target.writeBackDepth(nativeTargets.gbufferFbo);earlyFrames++;
        }
    }
    public void draw(FrameContext frame)throws Exception{
        if(IrisPacks.active()!=null)throw new IllegalStateException("Native source stage entered Iris");
        if(!AimohNativeGf2Registry.active())return;
        if(earlyFrame!=frame.index)throw new IllegalStateException("Native original colour ran before its GBuffer callback");
        if(lastFrame==frame.index)throw new IllegalStateException("Duplicate Native source frame");lastFrame=frame.index;
        ModelPass models=(ModelPass)AimohNativeGf2Environment.world("models");ModelDraws draws=models.draws();Targets nativeTargets=(Targets)AimohNativeGf2Environment.world("targets");
        FrameActors frozen=actors(frame,models);List<Actor> actors=frozen.colour;lastActors=actors.size();lastDraws=0;stageDraws.clear();stageOrder.clear();
        Set<Integer> alive=new HashSet<>();for(Actor actor:actors)alive.add((int)draws.palettes[actor.palette*12+24]);histories.keySet().retainAll(alive);rootPoses.keySet().removeIf(k->!alive.contains(k.actor));
        Set<Integer> submitted=frozen.submitted;
        for(var it=banks.entrySet().iterator();it.hasNext();){var entry=it.next();if(!submitted.contains(entry.getKey().actor)){entry.getValue().close();bankScopes.remove(entry.getKey());it.remove();}}
        for(var it=actorLighting.entrySet().iterator();it.hasNext();){var entry=it.next();if(!alive.contains(entry.getKey())){GL15.glDeleteBuffers(entry.getValue());it.remove();}}
        environment.retainActors(alive);
        if(actors.isEmpty()){previousValues=null;return;}
        try(AimohNativeSourceScope scope=new AimohNativeSourceScope()){
            environment.update(frame);ensure(frame.width,frame.height);target.targets(Map.of(0,colour,1,target.sourceNormal(),2,target.sourceMotion()));target.begin(nativeTargets.depth);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,nativeTargets.hdrFbo);GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,target.framebuffer());GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL30.glBlitFramebuffer(0,0,width,height,0,0,width,height,GL11.GL_COLOR_BUFFER_BIT,GL11.GL_NEAREST);
            GL44.glClearTexImage(target.sourceMotion(),0,GL11.GL_RGBA,GL11.GL_FLOAT,new float[]{65504,65504,0,0});
            transferOutputs(frame,nativeTargets,true);
            Map<String,float[]> values=AimohNativeSourceSpace.values(frame,environment.direction,previousValues);AimohNativeLocalLights frameLights=AimohNativeLocalLights.capture(frame);
            List<Ready> ready=new ArrayList<>(actors.size());for(Actor actor:actors)ready.add(prepareActor(frame,draws,actor,values,frameLights));
            captureRelief(models);
            float[] depth=new float[draws.count];var position=new org.joml.Vector3f();
            for(Ready actor:ready)for(Draw draw:actor.actor.draws){
                int at=draw.index*68+24;
                frame.view.transformPosition(position.set(draws.values[at],draws.values[at+1],draws.values[at+2]));
                depth[draw.index]=-position.z;
            }
            // Keep the host's palette and draw buffers attached across the source passes.
            try(AutoCloseable bindings=modelBindings(models)){
                for(String stage:STAGES){
                    List<Job> jobs=new ArrayList<>();
                    for(Ready actor:ready)for(Draw draw:actor.actor.draws){
                        JSONObject pass=materials.optionalPass(draw.material,stage);if(pass==null)continue;
                        jobs.add(new Job(actor,draw,queues.computeIfAbsent(pass,p->queue(materials.material(draw.material),p)),depth[draw.index]));
                    }
                    jobs.sort(Comparator.comparingInt(Job::queue).thenComparingDouble(j->j.queue>2500?-j.depth:j.depth).thenComparingInt(j->j.ready.identity).thenComparingInt(j->j.draw.ordinal));
                    if(!jobs.isEmpty())stageOrder.add(stage);
                    for(Job job:jobs)drawPrepared(frame,models,draws,job.ready,job.draw,stage);
                }
            }
            for(Ready actor:ready)histories.put(actor.identity,new History(actor.actor.model.getString("pmx_sha256"),actor.placement.clone(),actor.eye.clone(),frames,frame.jitterX/width,frame.jitterY/height));
            // Original capture: late hair/outline passes can write depth, and MRT2 is consumed after them.
            target.writeBackDepth(nativeTargets.gbufferFbo);
            transferOutputs(frame,nativeTargets,false);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,target.framebuffer());GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,nativeTargets.hdrFbo);GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL30.glBlitFramebuffer(0,0,width,height,0,0,width,height,GL11.GL_COLOR_BUFFER_BIT,GL11.GL_NEAREST);
            previousValues=values;frames++;
        }
    }
    private void transferOutputs(FrameContext frame,Targets nativeTargets,boolean initialize){
        motionBridge.use();motionBridge.setInt("initializeNormals",initialize?1:0);
        boolean colourTransfer=!initialize&&display!=null&&display.prepare(frame,motionBridge);
        motionBridge.setInt("gf2TransferOn",colourTransfer?1:0);
        motionBridge.sampler("gf2TransferOwnership",5);GL13.glActiveTexture(GL13.GL_TEXTURE0+5);GL11.glBindTexture(GL11.GL_TEXTURE_2D,nativeTargets.albedo);GL33.glBindSampler(5,0);
        GL42.glBindImageTexture(3,colour,0,false,0,GL15.GL_READ_WRITE,GL30.GL_RGBA16F);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,target.sourceMotion());GL33.glBindSampler(0,0);
        GL42.glBindImageTexture(0,nativeTargets.velocity,0,false,0,GL15.GL_READ_WRITE,GL30.GL_RGBA16F);
        GL42.glBindImageTexture(1,nativeTargets.normal,0,false,0,GL15.GL_READ_WRITE,GL30.GL_RGB10_A2);
        GL42.glBindImageTexture(2,target.sourceNormal(),0,false,0,GL15.GL_READ_WRITE,GL30.GL_RGBA16F);
        GL43.glDispatchCompute((width+15)/16,(height+15)/16,1);
        GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT|GL42.GL_FRAMEBUFFER_BARRIER_BIT);
    }
    private Ready prepareActor(FrameContext frame,ModelDraws draws,Actor actor,Map<String,float[]> nativeValues,AimohNativeLocalLights frameLights)throws Exception{
        int first=actor.draws.get(0).index;float[] placement=Arrays.copyOfRange(draws.values,first*68,first*68+16),eye={frame.eyeX,frame.eyeY,frame.eyeZ};
        float[] low={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY},high={Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
        for(Draw draw:actor.draws){int at=draw.index*68+24;float radius=draws.values[at+3];for(int c=0;c<3;c++){low[c]=Math.min(low[c],draws.values[at+c]-radius);high[c]=Math.max(high[c],draws.values[at+c]+radius);}}
        float[] sphere=new float[4];float diagonal=0;for(int c=0;c<3;c++){sphere[c]=(low[c]+high[c])*.5f;float half=(high[c]-low[c])*.5f;diagonal+=half*half;}sphere[3]=(float)Math.sqrt(diagonal);
        localLights=frameLights.select(sphere);
        float[] engine=Arrays.copyOfRange(draws.values,first*68+20,first*68+24);environment.actorLight(engine);actorEngineLight=engine;
        int identity=(int)draws.palettes[actor.palette*12+24];History old=histories.get(identity);boolean history=old!=null&&old.asset.equals(actor.model.getString("pmx_sha256"))&&old.frame==frames-1&&!frame.scene.cameraCut;
        AimohGf2FrameInputs builder=builders.computeIfAbsent(actor.model.getString("pmx_sha256"),id->new AimohGf2FrameInputs(actor.model,data.manifest.getJSONObject("original_camera_fill")));
        AimohGf2FrameInputs.Inputs packet=builder.build(nativeValues,placement,eye,history?old.placement:placement,history?old.eye:eye,width,height,frame.time,history,localLights.points,new float[0],new float[0]);
        localLights.neutralAttenuation(packet.values());
        nativeLightCount=packet.nativeLightCount();unsupportedCarBeams=packet.unsupportedCarBeams();
        Map<String,float[]> input=new LinkedHashMap<>(packet.values());float[] center=Arrays.copyOfRange(draws.values,first*68+24,first*68+27);var shadow=environment.shadow(center,eye);
        // The cube already contains sky visibility and current local radiance. Do not attenuate room reflections again.
        input.put("unity_SpecCube0_HDR",new float[]{1,1,0,0});
        if(casterQuery!=0){
            org.joml.Vector4f clip=new org.joml.Vector4f(center[0],center[1],center[2],1);frame.plainViewProjection.transform(clip);
            geometryWitness=new JSONObject().put("native_bounds_center",center).put("native_clip_center",new float[]{clip.x,clip.y,clip.z,clip.w}).put("placement",placement).put("eye",eye);
        }
        int lighting=actorLighting.computeIfAbsent(identity,k->{int buffer=GL45.glCreateBuffers();GL45.glNamedBufferData(buffer,128L,GL15.GL_DYNAMIC_DRAW);return buffer;});
        GL45.glCopyNamedBufferSubData(environment.buffer,lighting,0,0,128);
        environment.reflection(frame,center,java.lang.Math.max(engine[3],0),identity);
        input.put("_MainLightWorldToShadow",shadow.sourceMatrices());input.put("_MainLightShadowParams",new float[]{1,1,0,0});input.put("_MainLightShadowmapSize",new float[]{1f/shadow.size(),1f/shadow.size(),shadow.size(),shadow.size()});input.put("_CascadeDepthBiasArray",new float[4]);input.put("_CascadeNormalBiasArray",new float[4]);
        if(packet.cameraFillSlot()>=0){float[] colors=input.get("_AdditionalLightsColor");float nativeLevel=java.lang.Math.max(engine[0],java.lang.Math.max(engine[1],engine[2]));for(int c=0;c<3;c++)colors[packet.cameraFillSlot()*4+c]*=nativeLevel;}
        return new Ready(actor,identity,old,history,placement,eye,builder,packet,input,localLights,shadow,materials.attributeTexture(actor.model),environment.cube,lighting,new HashMap<>());
    }
    private void drawPrepared(FrameContext frame,ModelPass models,ModelDraws draws,Ready ready,Draw draw,String stage)throws Exception{
        String root=ready.actor.model.getJSONObject("renderer_frames").getJSONObject("materials").optString(Integer.toString(draw.material),null);
        Map<String,float[]> input=ready.input;
        Local local=root==null?new Local(input,input.get("unity_WorldToObject"),normal(matrix(input.get("unity_ObjectToWorld"))),ready.packet.previousInverse()):ready.locals.computeIfAbsent(root,r->local(ready.builder,r,draws,draw,input,ready.history,ready.identity));
        AimohGf2OriginalProgram program=nativePrograms.source(draw.material,stage);characterControls(program,draw.material);program.registers(registers(program,draw.material,root,local.values,ready.identity,false,ready.lighting));program.textures(materials.textureBindings(program,draw.material,ready.cube,ready.shadow.texture(),environment.shadowSampler));
        program.nativeInputs(local.inverse,local.inverseNormal,ready.actor.model.getInt("attribute_records"));program.nativeFrame(ready.history?ready.old.placement:ready.placement,local.previousInverse,ready.history?ready.old.eye:ready.eye,new float[2],ready.history);
        program.nativeProgram().set("gf2NativeMotionJitter",ready.history?frame.jitterX/width-ready.old.jitterU:0,ready.history?frame.jitterY/height-ready.old.jitterV:0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0+126);GL11.glBindTexture(GL11.GL_TEXTURE_2D,ready.attributes);
        ready.lights.bind(program.nativeProgram(),frame);environment.bindIndirect(program.nativeProgram(),frame);
        if(nativePrograms.mainConsumer(program.fragmentHash))environment.bindMain(program.nativeProgram(),frame);
        GL20.glDrawBuffers(program.sourceOutputs());int issued=drawNative(frame,models,program,materials.state(draw.material,stage,false),draw.index);if(issued!=1)throw new IllegalStateException("Source job not issued by Native batches: "+stage+" / "+draw.index+" = "+issued);
        stageDraws.merge(stage,1,Integer::sum);lastDraws++;
    }
    private Local local(AimohGf2FrameInputs builder,String root,ModelDraws draws,Draw draw,Map<String,float[]> input,boolean history,int identity){
        RootKey key=new RootKey(identity,root);RootPose pose=rootPoses.get(key);if(pose==null||pose.frame!=frames){double[] current=builder.rootRotation(root,draws.palettes,draws.palette[draw.index]*12);pose=new RootPose(frames,current,pose!=null&&pose.frame==frames-1?pose.rotation:current);rootPoses.put(key,pose);}
        Matrix4d object=matrix(input.get("unity_ObjectToWorld")).mul(new Matrix4d().set(new Matrix3d().set(pose.rotation)));
        Matrix4d previous=matrix(input.get("unity_MatrixPreviousM")).mul(new Matrix4d().set(new Matrix3d().set(pose.previous)));if(!history)previous.set(object);
        Map<String,float[]> values=new LinkedHashMap<>(input);values.put("unity_ObjectToWorld",flat(object));values.put("unity_WorldToObject",flat(new Matrix4d(object).invert()));values.put("unity_MatrixPreviousM",flat(previous));
        return new Local(values,flat(new Matrix4d(object).invert()),normal(object),flat(new Matrix4d(previous).invert()));
    }
    private Map<Integer,Integer> registers(AimohGf2OriginalProgram program,int material,String root,Map<String,float[]> input,int actor,boolean early){
        return registers(program,material,root,input,actor,early,environment.buffer);
    }
    private RegisterLayout registerLayout(String sha,int binding){
        return registerLayouts.computeIfAbsent(sha+":"+binding,key->{
            JSONObject layout=null;
            for(Object value:data.manifest.getJSONObject("consumed").getJSONObject(sha).getJSONArray("buffers")){
                JSONObject candidate=(JSONObject)value;if(candidate.getInt("register")==binding){layout=candidate;break;}
            }
            if(layout==null)throw new IllegalArgumentException("Source consumed register absent");
            List<String> names=List.of("unity_SHAr","unity_SHAg","unity_SHAb","unity_SHBr","unity_SHBg","unity_SHBb","unity_SHC");Integer sh=null,main=null;
            for(Object value:layout.getJSONArray("fields")){
                JSONObject field=(JSONObject)value;String name=field.getString("name");int offset=field.getInt("m_Index"),index=names.indexOf(name);
                if(index>=0){int start=offset-index*16;if(sh!=null&&sh!=start)throw new IllegalArgumentException("Source SH not contiguous");sh=start;}
                if(name.equals("_MainLightColor"))main=offset;
            }
            return new RegisterLayout(layout,sh,main);
        });
    }
    private Map<Integer,Integer> registers(AimohGf2OriginalProgram program,int material,String root,Map<String,float[]> input,int actor,boolean early,int lighting){
        Map<Integer,Integer> result=new HashMap<>();String scope=frames+":"+actor+":"+early+":"+shadowPhase;
        for(int slot=0;slot<6;slot++)if(program.registerBytes(slot)>0){
            String sha=slot<3?program.fragmentHash:program.vertexHash;int binding=slot%3;RegisterLayout layout=registerLayout(sha,binding);
            if(layout.source.getString("name").equals("UnityPerMaterial")){result.put(slot,materials.materialBuffer(material));continue;}
            BankKey key=new BankKey(actor,sha,binding,root);AimohGf2RegisterBank bank=banks.get(key);if(bank==null){bank=new AimohGf2RegisterBank(layout.source);banks.put(key,bank);}
            if(!scope.equals(bankScopes.get(key))){
                bank.update(input,++revision);
                if(!early&&layout.mainOffset!=null)GL45.glCopyNamedBufferSubData(lighting,bank.buffer,112,layout.mainOffset,16);
                if(!early&&layout.shOffset!=null)GL45.glCopyNamedBufferSubData(lighting,bank.buffer,0,layout.shOffset,112);
                GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_UNIFORM_BARRIER_BIT);bankScopes.put(key,scope);
            }result.put(slot,bank.buffer);
        }return result;
    }
    private void captureRelief(ModelPass models)throws Exception{
        GlProgram base=(GlProgram)AimohNativeGf2Environment.field(models,"gbuffer");
        if(reliefBaseId!=base.id()){reliefBaseId=base.id();for(int i=0;i<RELIEF_NAMES.length;i++)reliefLocations[i]=GL20.glGetUniformLocation(base.id(),RELIEF_NAMES[i]);}
        for(int i=0;i<RELIEF_NAMES.length;i++)if(reliefLocations[i]>=0)GL20.glGetUniformfv(base.id(),reliefLocations[i],reliefValues[i]);
    }
    private void characterControls(AimohGf2OriginalProgram program,int material){
        var settings=viewpoint.platform.AimohNativePackSelection.displaySettings();GlProgram gl=program.nativeProgram();gl.use();
        gl.set("gf2CharacterEnhancement",settings==null?0:settings.surfaceDetail(),settings==null?0:settings.skinGloss(),materials.surfaceWeight(material));
        if(gl.has("gf2CharacterOutlineWidth"))gl.set("gf2CharacterOutlineWidth",settings==null?1:settings.outlineWidth());
    }
    private int drawNative(FrameContext frame,ModelPass models,AimohGf2OriginalProgram program,JSONObject state,int selected)throws Exception{
        GlProgram gl=program.nativeProgram();gl.use();gl.setInt("uShadowPalette",0);gl.setInt("uIrisEntity",-1);gl.setInt("uIrisUntinted",0);gl.set("uIrisEye",frame.eyeX,frame.eyeY,frame.eyeZ);
        gl.set("gf2NativeMotionViewport",(float)frame.width,(float)frame.height);
        for(int i=0;i<RELIEF_NAMES.length;i++){
            String name=RELIEF_NAMES[i];if(reliefLocations[i]<0)continue;float[] values=reliefValues[i];
            switch(name){case "uReliefGrid"->gl.set(name,values[0],values[1],values[2],values[3]);case "uReliefCamera","uReliefFlowCells"->gl.set(name,values[0],values[1]);default->gl.set(name,values[0]);}
        }
        // Preserve the stock batch path, but visit only the already-selected draw.
        singleDraw[0]=selected;return models.batches.draw(singleDraw,1,i->i==selected,new ModelBatches.State(){public void set(int draw){program.state(state);}public void texture(int unit,int draw){}});
    }
    public String evidence(){return new JSONObject().put("frames",frames).put("early_frames",earlyFrames).put("early_draws",earlyDraws).put("actors",lastActors).put("original_stage_draws",lastDraws).put("stages",stageDraws).put("stage_order",stageOrder).put("actor_lighting_buffers",actorLighting.size()).put("native_programs",nativePrograms.count()).put("native_additional_light_inputs",nativeLightCount).put("native_local_selected_slots",localLights==null?0:localLights.kinds.length).put("native_local_kinds",localLights==null?new int[0]:localLights.kinds).put("native_local_indices",localLights==null?new int[0]:localLights.indices).put("native_local_fragment_consumers",nativePrograms.localConsumers()).put("native_indirect_fragment_consumers",nativePrograms.indirectConsumers()).put("native_actor_light",actorEngineLight).put("unsupported_car_beams",unsupportedCarBeams).put("shadow_views",shadowViews).put("shadow_draws",shadowDraws).put("visible_caster_views",visibleCasterViews).put("caster_query_is_fixture_only",casterQuery!=0).put("geometry_witness",geometryWitness).put("source_data_identity",data.identity).put("native_environment",environment.evidence()).put("original_display_transfer",display==null?JSONObject.NULL:display.evidence()).put("native_reflection_cube",environment.cube).put("native_main_shadow_consumers",nativePrograms.mainConsumers()).put("native_legacy_shadow_samplers",nativePrograms.legacySunSamplers()).put("scope","Native opaque depth/velocity retained; original source colour/stencil/shadow, Native cloud and actor ambient connected. Shared display transform and current-scene reflection are connected. Point/muzzle/spot services and actual Native bounce/flash-bounce feed source lighting. Actual six-direction room radiance is connected. Transparent/dynamic reflection coverage, conditional original effects, full authored motion and gameplay acceptance remain incomplete").toString();}
    @Override public void close()throws Exception{AimohNativeGf2Registry.detach(data);AimohNativeSourceScope.forget(motionBridge,(GlProgram)AimohNativeGf2Environment.field(target,"copy"));for(AimohGf2RegisterBank b:banks.values())b.close();for(int buffer:actorLighting.values())GL15.glDeleteBuffers(buffer);actorLighting.clear();if(display!=null)display.close();nativePrograms.close();materials.close();environment.close();motionBridge.delete();target.close();if(colour!=0)GL11.glDeleteTextures(colour);if(casterQuery!=0)GL15.glDeleteQueries(casterQuery);}
}
