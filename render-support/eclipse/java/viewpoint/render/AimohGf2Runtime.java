package viewpoint.render;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import org.json.*;
import org.joml.Matrix3d;
import org.joml.Matrix3f;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.*;
import viewpoint.iris.*;

/** Normal Java source-program runtime. The pack owns programs/data; the native
 * frame owns final PMX geometry, world lighting, depth and post-processing.
 */
public final class AimohGf2Runtime implements AutoCloseable {
    static final Map<IrisPrograms,Assets> PACKS=Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<IrisTargets,AimohGf2Runtime> FRAMES=new IdentityHashMap<>();
    static final class Assets implements AutoCloseable {
        final IrisPack pack;final AimohGf2PackData data;final AimohGf2PackMaterials materials;
        Assets(IrisPack pack)throws IOException{this.pack=pack;data=new AimohGf2PackData(pack.source);materials=new AimohGf2PackMaterials(data);}
        String kernel(String name)throws IOException{return text(data.manifest.getJSONObject("kernels").getJSONObject(name));}
        String text(JSONObject resource)throws IOException{return new String(data.bytes(resource),StandardCharsets.UTF_8);}
        @Override public void close(){materials.close();}
    }
    public static boolean enabled(IrisPipeline p){return PACKS.containsKey(p.programs);}
    public static void link(IrisPrograms programs,IrisPack pack)throws IOException{PACKS.put(programs,new Assets(pack));}
    public static void releasePrograms(IrisPrograms programs){AimohGf2SourceHost.released(programs);Assets old=PACKS.remove(programs);if(old!=null)old.close();}
    public static void release(IrisPipeline p){AimohGf2Runtime old=FRAMES.remove(p.targets);if(old!=null)old.close();}
    static AimohGf2Runtime frame(IrisPipeline p){
        Assets a=PACKS.get(p.programs);if(a==null)return null;
        AimohGf2Runtime current=FRAMES.get(p.targets);if(current!=null&&current.assets==a)return current;
        if(current!=null)current.close();try{
            float keyLight=AimohGf2SourceHost.numberOption(p.configured.values().get("characterKeyLightStrength"),1f,0f,10f);
            current=new AimohGf2Runtime(a,AimohGF2Services.actorMotion(p.programs),keyLight);FRAMES.put(p.targets,current);
            System.out.println("[AimohGF2] Screen mapping: TAA="+p.configured.values().get("TAA")+", TAA_UPSCALING="+p.configured.values().get("TAA_UPSCALING")+", effective scale="+AimohGf2Screen.scale(p.configured.values())+", actor motion="+current.actorMotion);
            return current;
        }catch(IOException e){throw new IllegalStateException("GF2 source runtime initialization",e);}
    }
    private final Assets assets;
    private final AimohGf2DepthStencil target;
    private final AimohGf2EclipseEnvironment environment;
    private final boolean actorMotion;
    private final AimohGf2RuntimeQueue queue;
    private final int shadowSampler;
    private AimohGf2WorldShadow worldBridge;
    private String worldKey;
    private final Map<Integer,History> histories=new HashMap<>();
    private final Map<String,AimohGf2FrameInputs> inputBuilders=new HashMap<>();
    private record BankKey(String sha,int binding,String root){}
    private final Map<BankKey,AimohGf2RegisterBank> banks=new HashMap<>();
    private record Scope(int frame,int actor,int phase){}
    private final Map<BankKey,Scope> bankScopes=new HashMap<>();
    private record RootKey(int actor,AimohGf2FrameInputs source,String root){}
    private record RootPose(int frame,double[] current,double[] previous){}
    private final Map<RootKey,RootPose> poses=new HashMap<>();
    private final Map<String,Boolean> options=new HashMap<>();
    private List<float[]> jitterRows;
    private record History(String asset,int frame,float[] model,float[] eye){}
    record Draw(int nativeIndex,int material,int ordinal){}
    private record Job(int queue,Draw draw,int material){}
    private record Local(Map<String,float[]> inputs,AimohGf2RuntimeQueue.Frame frame){}
    private int renderFrame;
    private int lastActors,lastDraws;
    public static String evidence(IrisPipeline p){AimohGf2Runtime r=FRAMES.get(p.targets);return r==null?"{}":new JSONObject().put("frames",r.renderFrame).put("actors",r.lastActors).put("native_source_draws",r.lastDraws).put("material_buffers",r.assets.materials.materialCount()).put("source_programs",r.assets.materials.programCount()).put("data_identity",r.assets.data.identity).put("python_callback",false).put("original_game_archive",false).put("native_probe_active",r.environment.localProbeActive).toString();}
    private long revision;
    private boolean closed;

    private AimohGf2Runtime(Assets assets,boolean actorMotion,float keyLight)throws IOException{
        this.assets=assets;this.actorMotion=actorMotion;
        target=new AimohGf2DepthStencil(actorMotion?assets.kernel("MOTION_DECODE"):null);
        queue=new AimohGf2RuntimeQueue(actorMotion);
        // Source materials select the cube mip with RMO.r * 6. Eclipse's atlas also uses linear roughness.
        String cube=assets.kernel("CUBE").replace(
            "float rough=(1.7-sqrt(max(0,2.89-2.8*float(level)/6)))/1.4;",
            "float rough=float(level)/6.0;");
        environment=new AimohGf2EclipseEnvironment(sourceKeyLight(assets.kernel("ENVIRONMENT_SH"),keyLight),cube);
        environment.configureLocalProbe(sourceShadowLight(assets.text(assets.data.manifest.getJSONObject("native_probe")),keyLight));
        shadowSampler=GL33.glGenSamplers();for(int[] p:new int[][]{{GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR},{GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR},{GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE},{GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE},{GL14.GL_TEXTURE_COMPARE_MODE,GL30.GL_COMPARE_REF_TO_TEXTURE},{GL14.GL_TEXTURE_COMPARE_FUNC,GL11.GL_LEQUAL}})GL33.glSamplerParameteri(shadowSampler,p[0],p[1]);
    }
    private static float[] flat(Matrix4d m){return new Matrix4f(m).get(new float[16]);}
    private static Matrix4d matrix(float[] m){return new Matrix4d(new Matrix4f().set(m));}
    private static float[] normal(Matrix4d m){return new Matrix3f(new Matrix4f(m)).transpose().get(new float[9]);}
    private boolean option(String name){return options.computeIfAbsent(name,key->{
        String value=AimohGf2SourceHost.option(key);
        if(value!=null)return value.equalsIgnoreCase("true");
        try{return Pattern.compile("(?m)^\\s*#define\\s+"+Pattern.quote(key)+"\\s*$").matcher(assets.pack.source.read("lib/settings.glsl")).find();}
        catch(IOException e){throw new IllegalStateException(e);}
    });}
    private float number(String name){String value=AimohGf2SourceHost.option(name);if(value!=null)return Float.parseFloat(value);throw new IllegalArgumentException("Native option missing: "+name);}
    static float[] clipJitter(IrisPipeline p,int width,int height){return frame(p).jitter(width,height);}
    private float[] jitter(int width,int height){
        if(!option("TAA"))return new float[2];
        if(jitterRows==null)try{
            String[] branch=assets.pack.source.read("lib/TAA_jitter.glsl").split("#else",2);String source=branch[option("RESPONSIVE_TAA")||option("DH_TAA_OVERRIDE")?0:1];
            Matcher match=Pattern.compile("vec2\\(\\s*([-+\\d.]+)\\s*,\\s*([-+\\d.]+)\\s*\\)(?:\\s*/\\s*([\\d.]+))?").matcher(source);List<float[]> rows=new ArrayList<>();
            while(match.find()){float divide=match.group(3)==null?1:Float.parseFloat(match.group(3));rows.add(new float[]{Float.parseFloat(match.group(1))/divide,Float.parseFloat(match.group(2))/divide});}
            if(rows.size()!=4&&rows.size()!=8)throw new IllegalArgumentException("Native jitter table changed");
            jitterRows=rows;
        }catch(IOException e){throw new IllegalStateException(e);}
        float[] v=jitterRows.get(Math.floorMod(AimohGf2SourceHost.nativeFrameCounter(),jitterRows.size()));
        return new float[]{v[0]/width,v[1]/height};
    }

    private static String sourceKeyLight(String source,float strength){
        if(strength==0f)return source;
        String output="])*nativeMainScale;";
        if(source.indexOf(output)<0||source.indexOf(output)!=source.lastIndexOf(output))
            throw new IllegalArgumentException("Source key light layout differs");
        // The captured GF2 key is 0.9 in the ready room and 1.2 in the activity scene.
        return source.replace(output,output+"""
            const float keyStrength=%s;
            const vec3 gf2Luminance=vec3(.2126,.7152,.0722);
            float keyEnergy=dot(mainLight.rgb,gf2Luminance);
            float excess=max(keyEnergy-.9,0.0);
            float shoulder=excess/(1.0+excess/.3);
            vec3 skyDiffuse=max(c[0],vec3(0.0));
            float skyEnergy=dot(skyDiffuse,gf2Luminance);
            float highStrength=clamp((keyStrength-2.0)/8.0,0.0,1.0);
            float daylight=smoothstep(.08,.45,keyEnergy);
            float excessFraction=clamp(min(keyStrength,2.0)*excess/max(keyEnergy,1e-6),0.0,1.0);
            if(skyEnergy>1e-6)
                mainLight.rgb=mix(mainLight.rgb,skyDiffuse*(keyEnergy/skyEnergy),max(excessFraction,highStrength*daylight));
            mainLight.rgb*=1.0-min(min(keyStrength,2.0)*(excess-shoulder),excess)/max(keyEnergy,1e-6);
            """.formatted(Float.toString(strength)));
    }

    private static String sourceShadowLight(String source,float strength){
        if(strength<=2f)return source;
        String output="actorSH[6].w=1;";
        if(source.indexOf(output)<0||source.indexOf(output)!=source.lastIndexOf(output))
            throw new IllegalArgumentException("Source diffuse probe layout differs");
        // The ready-room diffuse reference is about 1.67 times the legacy camera-fill reference.
        return source.replace(output,"""
            const float highStrength=%s;
            const vec3 gf2Luminance=vec3(.2126,.7152,.0722);
            vec3 localDiffuse=budgetedLevel*diffuseReference;
            float diffuseEnergy=dot(localDiffuse,gf2Luminance);
            float daylight=smoothstep(.08,.45,dot(sourceMain.rgb,gf2Luminance));
            if(diffuseEnergy>1e-6 && daylight>0.0){
                vec3 chroma=localDiffuse/diffuseEnergy;
                float blend=highStrength*daylight*visible;
                vec4 luminanceA=actorSH[0]*gf2Luminance.r+actorSH[1]*gf2Luminance.g+actorSH[2]*gf2Luminance.b;
                vec4 luminanceB=actorSH[3]*gf2Luminance.r+actorSH[4]*gf2Luminance.g+actorSH[5]*gf2Luminance.b;
                float missingDiffuse=max(1.67*diffuseEnergy-dot(mean+missing,gf2Luminance),0.0);
                for(int channel=0;channel<3;channel++){
                    actorSH[channel]=mix(actorSH[channel],luminanceA*chroma[channel],blend);
                    actorSH[channel+3]=mix(actorSH[channel+3],luminanceB*chroma[channel],blend);
                    actorSH[channel].w+=blend*missingDiffuse*chroma[channel];
                }
                actorSH[6].rgb=mix(actorSH[6].rgb,dot(actorSH[6].rgb,gf2Luminance)*chroma,blend);
            }
            """.formatted(Float.toString((strength-2f)/8f))+output)
            .replace("writeonly buffer ActorUnitySH","buffer ActorUnitySH");
    }

    void draw(boolean early)throws IOException{
        if(!early)renderFrame++;int frame=early?renderFrame+1:renderFrame;
        var actors=AimohGf2SourceHost.sourceState().actors;
        histories.keySet().retainAll(actors.keySet());poses.keySet().removeIf(k->!actors.containsKey(k.actor));
        if(!early){lastActors=actors.size();lastDraws=0;}
        if(early&&actorMotion)GL44.glClearTexImage(AimohGf2SourceHost.motionTarget(),0,GL30.GL_RG,GL11.GL_FLOAT,new float[]{65504,65504});
        if(actors.isEmpty())return;
        int width=AimohGf2SourceHost.width(),height=AimohGf2SourceHost.height();
        target.ensure(width,height);
        if(early){
            target.targets(Map.of(0,AimohGf2SourceHost.color(1),1,AimohGf2SourceHost.color(8),2,AimohGf2SourceHost.color(2),3,AimohGf2SourceHost.color(17)));
        }else{
            target.ensureOriginalOutputs();
            target.targets(actorMotion?Map.of(0,AimohGf2SourceHost.color(3),1,target.sourceNormal(),2,target.sourceMotion())
                :Map.of(0,AimohGf2SourceHost.color(3),1,target.sourceNormal()));
        }
        target.begin(AimohGf2SourceHost.depth());
        if(!early){
            GL30.glClearBufferfv(GL11.GL_COLOR,1,new float[4]);
            if(actorMotion)GL30.glClearBufferfv(GL11.GL_COLOR,2,new float[]{65504,65504,0,0});
            int[] main=AimohGf2SourceHost.gpuField("lightSourceColorSSBO");
            environment.updateFrame(AimohGf2SourceHost.color(20),AimohGf2SourceHost.color(21),1,main[0],main[1],1f/2400,renderFrame);
            int[] exposure=AimohGf2SourceHost.gpuField("exposureSSBO");
            environment.exposureView(exposure[0],exposure[1]);
        }
        boolean first=true;
        for(var actor:actors.values()){
            if(!first)target.clearActorStencil();
            drawActor(early,frame,actor);
            first=false;
        }
        target.writeBackDepth(AimohGf2SourceHost.nativeColorFbo());
        if(!early&&actorMotion)target.decodeMotion(AimohGf2SourceHost.motionTarget());
    }

    private void drawActor(boolean early,int frame,AimohGF2Services.Actor actor)throws IOException{
        int width=AimohGf2SourceHost.width(),height=AimohGf2SourceHost.height(),actorId=actor.id;JSONObject model=actor.model;
        AimohGf2FrameInputs builder=inputBuilders.computeIfAbsent(model.getString("pmx_sha256"),k->new AimohGf2FrameInputs(model,assets.data.manifest.getJSONObject("original_camera_fill")));
        float[] shadowCamera=AimohGf2SourceHost.sourceShadowCamera(actorId,!early);
        if(!early){
            Vector3d anchor=new Vector3d();matrix(Arrays.copyOfRange(shadowCamera,32,48)).invert().transformPosition(anchor);
            SceneData scene=AimohGf2SourceHost.context.scene;
            environment.probeActor(new float[]{(float)anchor.x,(float)anchor.y,(float)anchor.z},AimohGf2SourceHost.values.get("cameraPosition"),new float[]{number("MIN_LIGHT_AMOUNT"),number("MIN_LIGHT_AMOUNT_INSIDE"),AimohGf2SourceHost.values.get("nightVision")[0]},new float[]{scene.skyR,scene.skyG,scene.skyB},false);
        }
        History old=histories.get(actorId);boolean history=old!=null&&old.asset.equals(model.getString("pmx_sha256"))&&old.frame==frame-1&&!AimohGf2SourceHost.context.scene.cameraCut;
        float[] placement=actor.placement,eye=AimohGf2SourceHost.eye(),previous=history?old.model:placement,previousEye=history?old.eye:eye;
        // Native elapsed time is authoritative; frame/60 is only the controlled
        // fixture fallback when that native value is not exported.
        float[] time=AimohGf2SourceHost.values.get("frameTimeCounter");double seconds=time==null?frame/60d:time[0];
        AimohGf2FrameInputs.Inputs packet=builder.build(AimohGf2SourceHost.values,placement,eye,previous,previousEye,width,height,seconds,history,AimohGf2SourceHost.pointLightData(),AimohGf2SourceHost.torchLightData(),AimohGf2SourceHost.spotLightData());
        Map<String,float[]> inputs=new LinkedHashMap<>(packet.values());float renderScale=AimohGf2Screen.scale(AimohGf2SourceHost.pipeline.configured.values());
        AimohGf2Screen.inputs(inputs,width,height,renderScale);
        Matrix4d vp=matrix(Arrays.copyOfRange(shadowCamera,32,48));
        Matrix4d worldToShadow=new Matrix4d().translation(.5,.5,.5).scale(.5).mul(vp);float[] matrices=new float[80];System.arraycopy(flat(worldToShadow),0,matrices,0,16);int size=AimohGf2SourceHost.shadowSize();
        inputs.put("_MainLightWorldToShadow",matrices);inputs.put("_MainLightShadowParams",new float[]{1,1,0,0});inputs.put("_MainLightShadowmapSize",new float[]{1f/size,1f/size,size,size});inputs.put("_CascadeDepthBiasArray",new float[4]);inputs.put("_CascadeNormalBiasArray",new float[4]);
        int attributes=assets.materials.attributeTexture(model);float[] clipJitter=jitter(width,height);Matrix4d object=matrix(inputs.get("unity_ObjectToWorld"));
        AimohGf2RuntimeQueue.Frame base=new AimohGf2RuntimeQueue.Frame(inputs.get("unity_WorldToObject"),normal(object),model.getInt("attribute_records"),attributes,previous,packet.previousInverse(),previousEye,clipJitter,history,!early);
        List<Draw> draws=actor.draws;
        if(!early)lastDraws+=draws.size();
        GL11.glFrontFace(GL11.GL_CCW);
        if(!early)shadow(draws,inputs,base,shadowCamera,actorId,frame,packet.cameraFillSlot());
        String[] stages=early?new String[]{"GFCharForward"}:new String[]{"GFCharForward","GFHairShadow","GFCharFaceShadow","GFCharHairTransE","GFOutline","GFCharTransMrtWrtie","GFCharTransBack","GFCharTransFront","GFCharTransOutline"};
        Map<String,Local> roots=new HashMap<>();
        for(String stage:stages){List<Job> jobs=new ArrayList<>();for(Draw draw:draws){JSONObject material=assets.materials.material(draw.material);JSONObject pass=pass(material,stage);if(pass==null)continue;if(early&&!enabledNumber(pass.getJSONObject("state").get("zWrite")))continue;jobs.add(new Job(renderQueue(material,pass),draw,draw.material));}jobs.sort(Comparator.comparingInt(Job::queue));
            List<AimohGf2RuntimeQueue.Job> queued=new ArrayList<>();for(Job job:jobs){JSONObject material=assets.materials.material(job.material);String root=model.getJSONObject("renderer_frames").getJSONObject("materials").optString(Integer.toString(job.material),null);Local local=root==null?new Local(inputs,base):roots.computeIfAbsent(root,k->localFrame(builder,k,actorId,frame,inputs,base,job.draw.nativeIndex));
                AimohGf2OriginalProgram program=assets.materials.program(job.material,stage,early);if(!early&&material.getString("shader").endsWith("/eyeblend_add"))program.authoredExposureView(environment.exposureTexture,environment.exposureFloatOffset);
                queued.add(new AimohGf2RuntimeQueue.Job(program,assets.materials.state(job.material,stage,early),job.draw.nativeIndex,registers(program,job.material,root,local.inputs,frame,actorId,early?0:2,packet.cameraFillSlot()),assets.materials.textureBindings(program,job.material,environment.reflectionTexture,AimohGf2SourceHost.shadowTarget(),shadowSampler),local.frame));}
            queue.draw(queued,base,renderScale);
        }
        if(!early)histories.put(actorId,new History(model.getString("pmx_sha256"),frame,placement.clone(),eye.clone()));
    }

    private Local localFrame(AimohGf2FrameInputs builder,String root,int actor,int frame,Map<String,float[]> inputs,AimohGf2RuntimeQueue.Frame base,int draw){
        ModelDraws models=AimohGf2SourceHost.models.draws();RootKey key=new RootKey(actor,builder,root);RootPose pose=poses.get(key);
        if(pose==null||pose.frame!=frame){double[] current=builder.rootRotation(root,models.palettes,models.palette[draw]*12);double[] previous=pose!=null&&pose.frame==frame-1?pose.current:current;pose=new RootPose(frame,current,previous);poses.put(key,pose);}
        Matrix4d object=matrix(inputs.get("unity_ObjectToWorld")).mul(new Matrix4d().set(new Matrix3d().set(pose.current)));
        Matrix4d previous=matrix(inputs.get("unity_MatrixPreviousM")).mul(new Matrix4d().set(new Matrix3d().set(pose.previous)));if(!base.history())previous.set(object);
        Map<String,float[]> values=new LinkedHashMap<>(inputs);values.put("unity_ObjectToWorld",flat(object));values.put("unity_WorldToObject",flat(new Matrix4d(object).invert()));values.put("unity_MatrixPreviousM",flat(previous));
        return new Local(values,new AimohGf2RuntimeQueue.Frame(flat(new Matrix4d(object).invert()),normal(object),base.attributeCount(),base.attributeTexture(),base.previousModel(),flat(new Matrix4d(previous).invert()),base.previousEye(),base.jitter(),base.history(),base.sourceMrt()));
    }
    private static boolean enabledNumber(Object value){if(value instanceof Number n)return n.doubleValue()!=0;return ((JSONObject)value).getDouble("value")!=0;}
    private static JSONObject pass(JSONObject material,String name){for(Object value:material.getJSONArray("passes")){JSONObject p=(JSONObject)value;if(p.getString("name").equals(name)&&!p.getBoolean("disabled_by_material"))return p;}return null;}
    private static int renderQueue(JSONObject material,JSONObject pass){int value=material.getInt("render_queue_override");if(value>=0)return value;String text="Geometry";for(Object tag:pass.getJSONObject("state").getJSONObject("m_Tags").getJSONArray("tags")){JSONArray pair=(JSONArray)tag;if(pair.getString(0).equals("QUEUE"))text=pair.getString(1);}Matcher m=Pattern.compile("([A-Za-z]+)([+-]\\d+)?").matcher(text);if(!m.matches())throw new IllegalArgumentException("Source queue changed");int base=switch(m.group(1)){case "Background"->1000;case "Geometry"->2000;case "AlphaTest"->2450;case "Transparent"->3000;case "Overlay"->4000;default->throw new IllegalArgumentException("Unknown source queue");};return base+(m.group(2)==null?0:Integer.parseInt(m.group(2)));}

    private Map<Integer,Integer> registers(AimohGf2OriginalProgram program,int material,String root,Map<String,float[]> inputs,int frame,int actor,int phase,int cameraSlot){
        Map<Integer,Integer> result=new HashMap<>();JSONObject consumed=assets.data.manifest.getJSONObject("consumed");Scope scope=new Scope(frame,actor,phase);
        for(int slot=0;slot<6;slot++){
            if(program.registerBytes(slot)==0)continue;String sha=slot<3?program.fragmentHash:program.vertexHash;int binding=slot%3;JSONObject layout=null;for(Object value:consumed.getJSONObject(sha).getJSONArray("buffers")){JSONObject candidate=(JSONObject)value;if(candidate.getInt("register")==binding){layout=candidate;break;}}if(layout==null)throw new IllegalArgumentException("Source consumed register absent");
            if(layout.getString("name").equals("UnityPerMaterial")){result.put(slot,assets.materials.materialBuffer(material));continue;}
            boolean object=false;for(Object value:layout.getJSONArray("fields"))if(Set.of("unity_ObjectToWorld","unity_WorldToObject","unity_MatrixPreviousM").contains(((JSONObject)value).getString("name")))object=true;
            BankKey key=new BankKey(sha,binding,object?root:null);AimohGf2RegisterBank bank=banks.get(key);if(bank==null){bank=new AimohGf2RegisterBank(layout);banks.put(key,bank);}
            if(!Objects.equals(bankScopes.get(key),scope)){
                bank.update(inputs,++revision);if(phase!=0){List<String> sh=List.of("unity_SHAr","unity_SHAg","unity_SHAb","unity_SHBr","unity_SHBg","unity_SHBb","unity_SHC");Integer shOffset=null;
                    for(Object value:layout.getJSONArray("fields")){JSONObject field=(JSONObject)value;String name=field.getString("name");int offset=field.getInt("m_Index");
                        if(sh.contains(name)){int start=offset-sh.indexOf(name)*16;if(shOffset!=null&&shOffset!=start)throw new IllegalArgumentException("Source SH register is not contiguous");shOffset=start;}
                        if(name.equals("_MainLightColor"))environment.copyMainTo(bank.buffer,offset);if(name.equals("unity_SpecCube0_HDR"))environment.copySpecTo(bank.buffer,offset);if(name.equals("_AdditionalLightsColor")&&cameraSlot>=0)environment.copyCameraTo(bank.buffer,offset+cameraSlot*16);
                    }if(shOffset!=null)environment.copyTo(bank.buffer,shOffset);
                }bankScopes.put(key,scope);
            }result.put(slot,bank.buffer);
        }return result;
    }

    private void shadow(List<Draw> draws,Map<String,float[]> inputs,AimohGf2RuntimeQueue.Frame base,float[] camera,int actor,int frame,int cameraSlot)throws IOException{
        Matrix4d view=matrix(Arrays.copyOfRange(camera,0,16)),projection=matrix(Arrays.copyOfRange(camera,16,32)),vp=matrix(Arrays.copyOfRange(camera,32,48));Map<String,float[]> values=new LinkedHashMap<>(inputs);
        values.put("unity_MatrixV",flat(view));values.put("unity_MatrixInvV",flat(new Matrix4d(view).invert()));values.put("unity_CameraToWorld",flat(new Matrix4d(view).invert()));values.put("unity_MatrixVP",flat(vp));values.put("glstate_matrix_projection",flat(projection));
        int fbo=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),read=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),size=AimohGf2SourceHost.shadowSize();
        try{
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,AimohGf2SourceHost.shadowFramebuffer());GL11.glViewport(0,0,size,size);GL11.glDisable(GL11.GL_SCISSOR_TEST);GL11.glDepthMask(true);GL11.glClearDepth(1);GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);GL11.glDrawBuffer(GL11.GL_NONE);worldShadow(vp,size);
            List<AimohGf2RuntimeQueue.Job> jobs=new ArrayList<>();for(Draw draw:draws){if(pass(assets.materials.material(draw.material),"ShadowCaster")==null)continue;AimohGf2OriginalProgram program=assets.materials.program(draw.material,"ShadowCaster",false);jobs.add(new AimohGf2RuntimeQueue.Job(program,assets.materials.state(draw.material,"ShadowCaster",false),draw.nativeIndex,registers(program,draw.material,null,values,frame,actor,1,cameraSlot),assets.materials.textureBindings(program,draw.material,environment.reflectionTexture,AimohGf2SourceHost.shadowTarget(),shadowSampler)));}
            queue.draw(jobs,new AimohGf2RuntimeQueue.Frame(base.inverse(),base.inverseNormal(),base.attributeCount(),base.attributeTexture(),base.previousModel(),base.previousInverse(),base.previousEye(),new float[2],base.history(),false));
        }finally{GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,fbo);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read);GL11.glViewport(0,0,AimohGf2SourceHost.width(),AimohGf2SourceHost.height());}
    }
    private void worldShadow(Matrix4d sourceVp,int size)throws IOException{
        if(option("PLANET_CURVATURE"))throw new IllegalArgumentException("Curved world shadow requires its source-space contract");
        Matrix4d projection=matrix(AimohGf2SourceHost.values.get("shadowProjection"));Matrix4d view=matrix(AimohGf2SourceHost.nativeShadowView());Matrix4d nativeVp=new Matrix4d(projection).mul(view),sourceInverse=new Matrix4d(sourceVp).invert();
        Vector3d sourceAxis=sourceInverse.transformDirection(new Vector3d(0,0,1)),nativeAxis=new Matrix4d(nativeVp).invert().transformDirection(new Vector3d(0,0,1));if(sourceAxis.normalize().dot(nativeAxis.normalize())<.9999)throw new IllegalArgumentException("Source and native shadow rays differ");
        int nativeSize=AimohGf2SourceHost.worldShadowSize();double distance=AimohGf2SourceHost.worldShadowDistance();boolean distort=option("DISTORT_SHADOWMAP");Matrix4d product=new Matrix4d(nativeVp).mul(sourceInverse);if(Math.max(Math.abs(product.m20()),Math.abs(product.m21()))*nativeSize>.25)throw new IllegalArgumentException("Native shadow ray drifts across texels");
        Vector3d center=sourceInverse.transformPosition(new Vector3d());view.transformPosition(center);double bias=distance/nativeSize*4*.15*(1+Math.hypot(center.x,center.y)*(128/distance)*.1);
        String key=nativeSize+":"+distance+":"+distort;
        if(worldBridge==null||!key.equals(worldKey)){
            String params=assets.pack.source.read("lib/Shadow_Params.glsl");params=params.substring(params.indexOf("const float k")).replaceAll("\\bshadowDistance\\b",Double.toString(distance)).replaceAll("\\bshadowMapResolution\\b",nativeSize+".0");
            String fragment="#version 430\nuniform sampler2D nativeWorldDepth;uniform mat4 sourceVp,sourceInverse,nativeVp,nativeInverse;uniform float sourceSize,sourceDepthBias;\n"+params+(distort?"\n#define NATIVE_DISTORT_SHADOW\n":"")+"\nvoid main(){vec2 xy=gl_FragCoord.xy/sourceSize*2-1;vec4 point=sourceInverse*vec4(xy,0,1);point/=point.w;vec4 nativePoint=nativeVp*point;nativePoint/=nativePoint.w;vec4 projected=nativePoint;\n#ifdef NATIVE_DISTORT_SHADOW\nprojected=BiasShadowProjection(projected);\n#endif\nvec2 uv=projected.xy*.5+.5;if(any(lessThan(uv,vec2(0)))||any(greaterThanEqual(uv,vec2(1)))){gl_FragDepth=1;return;}float depth=textureLod(nativeWorldDepth,uv,0).r;if(depth>=1){gl_FragDepth=1;return;}vec4 hit=nativeInverse*vec4(nativePoint.xy,(depth*2-1)*6,1);hit/=hit.w;vec4 destination=sourceVp*hit;gl_FragDepth=clamp(destination.z/destination.w*.5+.5+sourceDepthBias,0,1);}";
            AimohGf2WorldShadow next=new AimohGf2WorldShadow(fragment);if(worldBridge!=null)worldBridge.close();worldBridge=next;worldKey=key;
        }
        double depthScale=Math.sqrt(sourceVp.m02()*sourceVp.m02()+sourceVp.m12()*sourceVp.m12()+sourceVp.m22()*sourceVp.m22());worldBridge.draw(AimohGf2SourceHost.worldShadowTarget(),size,flat(sourceVp),flat(nativeVp),(float)(bias*depthScale*.5));
    }
    @Override public void close(){if(closed)return;closed=true;for(AimohGf2RegisterBank b:banks.values())b.close();banks.clear();if(worldBridge!=null)worldBridge.close();environment.close();target.close();GL33.glDeleteSamplers(shadowSampler);histories.clear();poses.clear();}
}
