package viewpoint.render;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import viewpoint.iris.*;
import viewpoint.platform.IrisPacks;

/** Native source stages. No Python callbacks, instrumentation, or process env. */
public final class AimohGf2SourceHost {
    public static IrisPipeline pipeline;public static FrameContext context;
    public static ModelPass models;public static ShadowPass shadows;public static Map<String,float[]> values;
    public static boolean servicesActive;
    private static boolean earlyDone,lateDone;private static float[] frameShadowView;
    private static final Map<String,int[]> gpuFields=new HashMap<>();private static IrisPrograms gpuFieldOwner;
    private static IrisProgram reliefProgram;private static final Map<String,Integer> reliefLocations=new HashMap<>();private static final Map<String,float[]> reliefValues=new HashMap<>();
    private static final Field SPOT_RECORDS,SPOT_COUNT;
    static{try{SPOT_RECORDS=Spots.class.getDeclaredField("records");SPOT_COUNT=Spots.class.getDeclaredField("count");SPOT_RECORDS.setAccessible(true);SPOT_COUNT.setAccessible(true);}catch(ReflectiveOperationException e){throw new ExceptionInInitializerError(e);}}
    private static final java.util.regex.Pattern SOURCE_PART=java.util.regex.Pattern.compile("aimoh:pmx-part-gf2-(-?\\d+)-(\\d+)");
    private static final int[] singleDraw=new int[1];
    public static void prepare(IrisPipeline p,FrameContext f,ShadowPass shadow,ModelPass model,Map<String,float[]> frameValues){pipeline=p;context=f;models=model;shadows=shadow;values=frameValues;earlyDone=lateDone=false;frameShadowView=null;reliefValues.clear();}
    public static void released(IrisPrograms programs){if(pipeline!=null&&pipeline.programs==programs){pipeline=null;context=null;models=null;shadows=null;values=null;gpuFields.clear();gpuFieldOwner=null;reliefProgram=null;reliefLocations.clear();reliefValues.clear();}}
    public static void sourceEarly(IrisFrame host,IrisPipeline p,FrameContext f){
        if(!AimohGf2Runtime.enabled(p))return;if(earlyDone)throw new IllegalStateException("Duplicate source early stage");earlyDone=true;
        try(AimohGf2GlScope ignored=new AimohGf2GlScope()){AimohGf2Runtime.frame(p).draw(true);}catch(java.io.IOException e){throw new IllegalStateException("GF2 early stage",e);}
    }
    public static void beforeSourcePass(IrisFrame host,IrisPipeline p,IrisPrograms.Pass pass,String kind,FrameContext f,Set<Integer> flips){
        if(!AimohGf2Runtime.enabled(p)||lateDone||!kind.equals("composite"))return;String name=pass.name();if(!name.matches("composite[0-9]+")||Integer.parseInt(name.substring(9))<3)return;
        if(!earlyDone)throw new IllegalStateException("Source color stage before depth");lateDone=true;servicesActive=true;
        try(AimohGf2GlScope ignored=new AimohGf2GlScope()){AimohGF2Services.beforeScreen(host,p,"composite2",f,flips);AimohGf2Runtime.frame(p).draw(false);AimohGf2IrisGlass.draw(p,f,shadows,models);AimohGF2Services.afterScreen(p,"composite2",f);}catch(java.io.IOException e){throw new IllegalStateException("GF2 color stage",e);}finally{servicesActive=false;}
    }
    public static int drawNative(AimohGf2OriginalProgram program,JSONObject state,int selected){
        var gl=program.nativeProgram();gl.use();gl.setInt("uShadowPalette",0);gl.setInt("uIrisEntity",-1);gl.setInt("uIrisUntinted",0);gl.set("uIrisEye",context.eyeX,context.eyeY,context.eyeZ);
        IrisProgram original=pipeline.programs.draws.get(IrisPrograms.Draw.ENTITIES);if(original==null)throw new IllegalStateException("Native entity program missing");
        if(reliefProgram!=original){reliefProgram=original;reliefLocations.clear();reliefValues.clear();}
        for(String name:List.of("uReliefGrid","uReliefCamera","uReliefScale","uReliefSplit","uReliefFlowCells")){
            int location=reliefLocations.computeIfAbsent(name,k->GL20.glGetUniformLocation(original.gl.id(),k));if(location<0)continue;float[] saved=reliefValues.get(name);
            if(saved==null){var buffer=BufferUtils.createFloatBuffer(4);GL20.glGetUniformfv(original.gl.id(),location,buffer);saved=new float[]{buffer.get(0),buffer.get(1),buffer.get(2),buffer.get(3)};reliefValues.put(name,saved);}
            switch(name){case "uReliefGrid"->gl.set(name,saved[0],saved[1],saved[2],saved[3]);case "uReliefCamera","uReliefFlowCells"->gl.set(name,saved[0],saved[1]);default->gl.set(name,saved[0]);}
        }
        try{models.begin();singleDraw[0]=selected;return models.batches.draw(singleDraw,1,i->i==selected,new ModelBatches.State(){public void set(int draw){program.state(state);}public void texture(int unit,int draw){}});}finally{models.end();}
    }
    public static boolean sourceMesh(int i){
        if(context==null||i<0||i>=models.draws().count)return false;
        ModelDraws draws=models.draws();var mesh=draws.meshes[i];
        if(mesh==null||mesh.getPath()==null||!mesh.getPath().getPath().startsWith("aimoh:pmx-part-gf2-"))return false;
        var assets=AimohGf2Runtime.PACKS.get(pipeline.programs);
        return assets!=null&&assets.data.modelForPalette(draws,draws.palette[i])!=null;
    }
    public static int[][] sourceActors(){
        var found=new ArrayList<int[]>();ModelDraws d=models.draws();
        for(AimohGF2Services.Actor actor:sourceState().actors)for(int i=0;i<d.count;i++)if(sourceMesh(i)){
            int at=d.palette[i]*12;if(at<0||at+26>=d.bones*12)continue;
            if((int)d.palettes[at+24]!=actor.id)continue;
            found.add(new int[]{actor.id,actor.slot,(int)d.palettes[at+12],(int)d.palettes[at+13]});break;
        }
        return found.toArray(int[][]::new);
    }
    public static int[][] sourceDraws(int actorId){
        var found=new ArrayList<int[]>();ModelDraws draws=models.draws();
        for(int i=0;i<draws.count;i++)if((draws.flags[i]&ModelDraws.COLOUR)!=0&&sourceMesh(i)){
            int at=draws.palette[i]*12;if(at<0||at+26>=draws.bones*12||(int)draws.palettes[at+24]!=actorId)continue;
            var match=SOURCE_PART.matcher(draws.meshes[i].getPath().getPath());if(match.matches())found.add(new int[]{i,Integer.parseInt(match.group(1)),Integer.parseInt(match.group(2))});
        }
        return found.toArray(int[][]::new);
    }
    public static int color(int slot){return pipeline.targets.buffers[slot].current();}
    public static int depth(){return pipeline.targets.depth;}
    public static int width(){return context.width;}
    public static int height(){return context.height;}
    public static float[] eye(){return new float[]{context.eyeX,context.eyeY,context.eyeZ};}
    public static int nativeColorFbo(){return pipeline.targets.framebuffer(List.of(3),false,1,-1);}
    public static int motionTarget(){
        AimohGF2Services.State state=sourceState();if(state.motion<=0)throw new IllegalStateException("Native character history target absent");return state.motion;
    }
    private static AimohGF2Services.State sourceState(){
        AimohGF2Services.State state=AimohGF2Services.sourceState(pipeline);if(state==null)throw new IllegalStateException("Actual character frame state absent");return state;
    }
    public static int shadowTarget(){return sourceState().shadow;}
    public static int shadowFramebuffer(){return sourceState().shadowFbo;}
    public static int shadowSize(){return sourceState().atlasSize;}
    public static int worldShadowTarget(){return pipeline.shadows.depthOpaque;}
    public static int worldShadowSize(){
        int old=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);GL11.glBindTexture(GL11.GL_TEXTURE_2D,worldShadowTarget());
        int size=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH);GL11.glBindTexture(GL11.GL_TEXTURE_2D,old);return size;
    }
    public static float worldShadowDistance(){return pipeline.shadowDistance;}
    private static AimohGF2Services.Actor sourceActor(int id){return sourceState().actors.stream().filter(a->a.id==id).findFirst().orElseThrow(()->new IllegalArgumentException("Missing frozen source actor "+id));}
    public static int nativeFrameCounter(){
        IrisProgram program=pipeline.programs.draws.get(IrisPrograms.Draw.ENTITIES);int location=GL20.glGetUniformLocation(program.gl.id(),"frameCounter");
        if(location>=0)return GL20.glGetUniformi(program.gl.id(),location);
        float[] value=values.get("frameCounter");if(value==null)throw new IllegalStateException("Native frame counter unavailable");return(int)value[0];
    }
    public static String option(String name){return pipeline.configured.values().get(name);}
    public static float[] pointLightData(){
        int count=WorldRenderer.off(3)?0:Math.min(context.scene.lightCount,8);float[] result=new float[count*8];
        for(int i=0;i<count;i++)System.arraycopy(context.scene.lights,i*12,result,i*8,8);
        return result;
    }
    public static float[] torchLightData(){return Arrays.copyOf(context.lights.torches,Math.min(context.scene.torches.count,8)*11);}
    public static float[] spotLightData(){
        if(shadows==null)throw new IllegalStateException("Actual spotlight frame owner absent");
        try{int count=SPOT_COUNT.getInt(shadows.spots);var buffer=((java.nio.FloatBuffer)SPOT_RECORDS.get(shadows.spots)).duplicate();buffer.position(0);buffer.limit(count*24);float[] result=new float[count*24];buffer.get(result);return result;}
        catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    public static Object programIdentity(){return pipeline.programs;}
    public static int[] gpuField(String variable){
        if(gpuFieldOwner!=pipeline.programs){gpuFields.clear();gpuFieldOwner=pipeline.programs;}
        int[] descriptor=gpuFields.get(variable);
        if(descriptor==null){
            search:for(var passes:pipeline.programs.passes.values())for(var pass:passes){
                List<IrisProgram> entries=new ArrayList<>(pass.computes());if(pass.program()!=null)entries.add(pass.program());
                for(IrisProgram entry:entries)for(String name:List.of(variable,"SSBO1."+variable,"SSBO0."+variable)){
                    int index=GL43.glGetProgramResourceIndex(entry.gl.id(),GL43.GL_BUFFER_VARIABLE,name);if(index==-1)continue;
                    int[] data=new int[2];GL43.glGetProgramResourceiv(entry.gl.id(),GL43.GL_BUFFER_VARIABLE,index,new int[]{GL43.GL_OFFSET,GL43.GL_BLOCK_INDEX},null,data);
                    int[] binding=new int[1];GL43.glGetProgramResourceiv(entry.gl.id(),GL43.GL_SHADER_STORAGE_BLOCK,data[1],new int[]{GL43.GL_BUFFER_BINDING},null,binding);
                    descriptor=new int[]{binding[0],data[0]};gpuFields.put(variable,descriptor);break search;
                }
            }
        }
        if(descriptor==null)throw new IllegalStateException("Native Eclipse GPU field missing: "+variable);
        Integer buffer=pipeline.images.buffers.get(descriptor[0]);if(buffer==null)throw new IllegalStateException("Native Eclipse SSBO binding missing");
        return new int[]{buffer,descriptor[1]};
    }
    public static float[] gpuVector(String variable,int components){
        int[] field=gpuField(variable);var data=BufferUtils.createFloatBuffer(components);GL45.glGetNamedBufferSubData(field[0],field[1],data);float[] values=new float[components];data.get(values);return values;
    }
    public static float[] nativeShadowView(){
        if(frameShadowView==null)frameShadowView="true".equals(pipeline.configured.values().get("CUSTOM_MOON_ROTATION"))?gpuVector("customShadowMatrixSSBO",16):values.get("shadowModelView");
        return frameShadowView;
    }
    public static float[] sourceShadowCamera(int id){
        return sourceShadowCamera(id,true);
    }
    public static float[] sourceShadowCamera(int id,boolean forShadowDraw){
        AimohGF2Services.Actor actor=sourceActor(id);
        // The host clamps near-horizon shadow elevation; import its actual ray, not the unclamped lighting vector.
        // The early depth/normal variant has no shadow output; keep its CPU inputs without forcing an early GPU wait.
        float[] direction=values.get("lightDir");
        var light=forShadowDraw?new org.joml.Matrix4f().set(nativeShadowView()).invert().transformDirection(new org.joml.Vector3f(0,0,1)).normalize():new org.joml.Vector3f(direction[0],direction[1],direction[2]).normalize();
        var up=Math.abs(light.y)>.98f?new org.joml.Vector3f(0,0,1):new org.joml.Vector3f(0,1,0);
        var eye=new org.joml.Vector3f(light).mul(2*actor.radius).add(actor.center);float[] data=new float[48];
        var view=new org.joml.Matrix4f().lookAt(eye,actor.center,up);view.get(data,0);
        var projection=new org.joml.Matrix4f().setOrtho(-actor.radius,actor.radius,-actor.radius,actor.radius,actor.radius*.5f,actor.radius*3.5f);projection.get(data,16);
        projection.mul(view).get(data,32);return data;
    }
    public static float[] actorModel(int id){return sourceActor(id).model.get(new float[16]);}

    private static AimohGf2Runtime.Assets drawnAssets(){var active=IrisPacks.drawn();if(active==null)return null;for(var value:AimohGf2Runtime.PACKS.values())if(value.pack==active.pack())return value;return null;}
    public static String profileKey(int token0,int token1){var active=IrisPacks.drawn();var assets=drawnAssets();if(active==null||assets==null||assets.data.model(token0,token1)==null)return null;return assets.data.identity+":"+active.generation();}
    public static String modelDescriptor(String key,int token0,int token1,int vertices,int materials){var assets=drawnAssets();if(assets==null||!Objects.equals(key,profileKey(token0,token1)))throw new IllegalStateException("Source profile changed while loading");JSONObject model=assets.data.model(token0,token1,vertices,materials);if(model==null)return null;JSONArray shaders=new JSONArray();for(Object value:assets.data.manifest.getJSONArray("materials"))shaders.put(((JSONObject)value).getString("shader"));return new JSONObject().put("model",model).put("material_shaders",shaders).toString();}
    public static byte[] resourceBytes(String key,String path)throws java.io.IOException {var assets=drawnAssets();var active=IrisPacks.drawn();if(assets==null||!Objects.equals(key,assets.data.identity+":"+active.generation()))throw new IllegalStateException("Source resource profile changed");return assets.data.bytes(path);}
}
