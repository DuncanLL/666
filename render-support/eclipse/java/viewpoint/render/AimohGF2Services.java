package viewpoint.render;

import java.io.File;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import org.json.JSONObject;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import viewpoint.iris.*;
import viewpoint.platform.GlProgram;
import zombie.core.textures.Texture;

/** Optional pack-owned character stages. Reuses the host's frozen ModelDraws. */
public final class AimohGF2Services {
    public static State sourceState(IrisPipeline p){return STATES.get(p.targets);}

    private static final Map<IrisPrograms,Bundle> BUNDLES=new IdentityHashMap<>();
    private static final Map<IrisTargets,State> STATES=new IdentityHashMap<>();
    private static final int SCRATCH=95;
    private static final Method GEOMETRY,ENTITIES,PASS,SCREEN;
    private static final Field EXPORT,SOURCES,DRAWN;
    private static boolean scopedDepth,stageDepthWrite,compilingEclipse;
    private static ModelBatches.Filter scopedDrawFilter;
    public static ModelBatches.Filter filter(ModelBatches.Filter original){
        ModelBatches.Filter selected=scopedDrawFilter;
        if(selected!=null)return n->selected.take(n)&&original.take(n);
        for(Bundle b:BUNDLES.values())if(b.eclipse&&b.nativeState!=null&&b.nativeEntityProgram==GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)){
            State s=b.nativeState;return n->original.take(n)&&!AimohGf2SourceHost.sourceMesh(n)&&(n<0||n>=s.drawMaterial.length||s.drawMaterial[n]<0);
        }
        return original;
    }
    private static int stageDepthFunc,scopedCull=-1;
    /** ModelPass.begin resets depth internally; apply the stage state after that reset. */
    public static void modelState(){if(scopedDepth){GL11.glDepthMask(stageDepthWrite);GL11.glDepthFunc(stageDepthFunc);}}
    public static void modelCull(){if(scopedCull==0)GL11.glDisable(GL11.GL_CULL_FACE);else if(scopedCull>0){GL11.glEnable(GL11.GL_CULL_FACE);GL11.glCullFace(scopedCull);}}
    static {
        try {
            GEOMETRY=IrisPrograms.class.getDeclaredMethod("geometry",IrisPrograms.Draw.class,ProgramSet.Program.class);GEOMETRY.setAccessible(true);
            ENTITIES=IrisGeometry.class.getDeclaredMethod("entities",IrisPipeline.class,Map.class,FrameContext.class,ShadowPass.class,IrisProgram.class,boolean.class,Runnable.class);ENTITIES.setAccessible(true);
            PASS=IrisPrograms.class.getDeclaredMethod("pass",ProgramSet.Program.class);PASS.setAccessible(true);
            SCREEN=IrisFrame.class.getDeclaredMethod("screen",IrisPipeline.class,IrisPrograms.Pass.class,String.class,FrameContext.class,boolean.class,Set.class);SCREEN.setAccessible(true);
            EXPORT=IrisPrograms.class.getDeclaredField("sources");EXPORT.setAccessible(true);
            SOURCES=ModelPass.class.getDeclaredField("sources");SOURCES.setAccessible(true);
            DRAWN=DrawSources.class.getDeclaredField("drawn");DRAWN.setAccessible(true);

        } catch(ReflectiveOperationException e){throw new ExceptionInInitializerError(e);}
    }
    public static Map<String,String> macros(Map<String,String> input){Map<String,String> result=new LinkedHashMap<>(input);result.put("VIEWPOINT_DANRP_HOST","1");result.put("VIEWPOINT_GF2_MODEL_SSBO","1");result.put("VIEWPOINT_DANRP_NATIVE_LIGHTS","1");return result;}
    private static boolean singleAlbedoProgram(String name){return name.equals("gbuffers_dan_trans")||name.equals("gbuffers_dan_trans_motion")||name.equals("gbuffers_dan_eyelash");}
    // Units 112..129 are unused by this model draw; avoid 130 (cloud shadow) and 139..141 (native lights).
    public static int firstUserUnit(String name,GlProgram program){
        if(compilingEclipse||singleAlbedoProgram(name))return 112;
        if(name.startsWith("gbuffers_dan_")||name.startsWith("dan_sky_")||name.equals("dan_shadow_scatter")||program.has("vpDanActorShadowCmp")||program.has("vpDanMotionTex")||program.has("vpDanFGD"))return 142;
        return 96; // Preserve the unmodified host's namespace for every ordinary shader.
    }
    public static int nextUserUnit(String name,int next){if(compilingEclipse)return next==130?131:next==139?142:next;return singleAlbedoProgram(name)&&next==130?142:next;}
    private static RuntimeException failure(Throwable e){if(e instanceof InvocationTargetException&&e.getCause()!=null)e=e.getCause();return e instanceof RuntimeException?(RuntimeException)e:new IllegalStateException("DanRP character stage",e);}

    static final class Bundle {
        final JSONObject manifest;
        final boolean eclipse;
        final Map<String,IrisProgram> stages=new LinkedHashMap<>();
        int nativeEntityProgram;State nativeState;
        final int[] materialFlags;
        IrisPrograms.Pass scatter,skyBase,skyFilter;
        int rimDepthSampler,moonDirectionOffset=-1;
        Bundle(JSONObject m){
            manifest=m;eclipse="ECLIPSE".equals(m.optString("render_backend"));materialFlags=new int[m.getJSONArray("submesh_materials").length()];
            JSONObject roles=m.optJSONObject("draw_roles");
            if(roles!=null){String[] names={"face","eye","eye_overlay","emotion"};
                for(int bit=0;bit<names.length;bit++){var ids=roles.getJSONArray(names[bit]);for(int i=0;i<ids.length();i++)materialFlags[ids.getInt(i)]|=1<<bit;}}
            var trans=m.getJSONArray("transparent_materials");for(int i=0;i<trans.length();i++)materialFlags[trans.getJSONObject(i).getInt("material")]|=16;
        }
        void delete(){for(IrisProgram p:stages.values())p.delete();stages.clear();for(IrisPrograms.Pass p:new IrisPrograms.Pass[]{scatter,skyBase,skyFilter})if(p!=null&&p.program()!=null)p.program().delete();scatter=skyBase=skyFilter=null;if(rimDepthSampler!=0)GL33.glDeleteSamplers(rimDepthSampler);rimDepthSampler=0;}
    }
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
                var source=AimohGf2Runtime.PACKS.get(programs).data.manifest;
                var definitions=new org.json.JSONArray();for(Object value:source.getJSONArray("models")){JSONObject m=(JSONObject)value;String sha=m.getString("pmx_sha256");definitions.put(new JSONObject().put("token0",Integer.parseInt(sha.substring(0,6),16)).put("token1",Integer.parseInt(sha.substring(6,12),16)).put("material_offset",0).put("submesh_materials",new org.json.JSONArray()));}
                JSONObject sourceManifest=new JSONObject().put("render_backend","ECLIPSE").put("token0",0).put("token1",0).put("models",definitions).put("submesh_materials",new org.json.JSONArray()).put("transparent_materials",new org.json.JSONArray()).put("shadow_resolution",2048);
                b=new Bundle(sourceManifest);b.nativeEntityProgram=programs.draws.get(IrisPrograms.Draw.ENTITIES).gl.id();
                b.skyBase=(IrisPrograms.Pass)PASS.invoke(programs,new ProgramSet.Program("dan_sky_base","danrp/sky_base.vsh",null,"danrp/sky_base.fsh",List.of()));
                b.skyFilter=(IrisPrograms.Pass)PASS.invoke(programs,new ProgramSet.Program("dan_sky_filter","danrp/sky_filter.vsh",null,"danrp/sky_filter.fsh",List.of()));
                if(b.skyBase.program()==null||b.skyFilter.program()==null)throw new IllegalStateException("Native filtered sky stages missing");BUNDLES.put(programs,b);return;
            }
        } catch(Throwable e){if(b!=null)b.delete();AimohGf2Runtime.releasePrograms(programs);programs.delete();throw failure(e);}
    }
    public static void releasePrograms(IrisPrograms programs){AimohGf2Runtime.releasePrograms(programs);Bundle b=BUNDLES.remove(programs);if(b!=null)b.delete();}
    public static void release(IrisPipeline pipeline){AimohGf2IrisGlass.release(pipeline);AimohGf2Runtime.release(pipeline);State s=STATES.remove(pipeline.targets);if(s!=null)s.delete();}
    public static void beginFrame(IrisPipeline pipeline,FrameContext frame){
        if(BUNDLES.containsKey(pipeline.programs)&&frame.scene.cameraCut)pipeline.uniforms.reset();
    }
    public static long extraBytes(IrisPipeline pipeline){State s=STATES.get(pipeline.targets);return s==null?0L:s.bytes;}
    public static int texture(IrisTargets targets,String name){
        if(name.equals("uCellLight")||name.equals("uCellTable")||name.equals("uLampFields")||name.equals("uMuzzleShadow"))return 0; // Bound by the native service after the generic binder.
        State s=STATES.get(targets);if(s!=null)return s.texture(name);
        return name.startsWith("vpDan")?0:-1;
    }
    /** Native spatial lighting, using already updated house resources, no duplicate atlas. */
    public static void nativeLights(IrisTargets targets,IrisProgram entry){
        Integer band=entry.units.get("uBandLight");
        if(band!=null){MeshArena.bindLight(5,entry.gl);BandLight.bind(band);}
        Integer cell=entry.units.get("uCellLight"),table=entry.units.get("uCellTable");
        if(cell!=null&&table!=null)CellLightPages.bind(cell,table);
        State state=STATES.get(targets);Integer trans=entry.units.get("vpDanTransAlbedo");
        Integer torch=entry.units.get("uMuzzleShadow");
        if(torch!=null&&state!=null&&state.frameShadow!=null){
            state.frameShadow.muzzle.uniforms(entry.gl);
            try{GL13.glActiveTexture(GL13.GL_TEXTURE0+torch);GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY,state.frameShadow.lamps.texture());GL33.glBindSampler(torch,0);}
            finally{GL13.glActiveTexture(GL13.GL_TEXTURE0);}
        }
        if(state!=null&&trans!=null&&state.activeTransTexture>0)GL33.glBindSampler(trans,TextureFilter.MODELS.sampler(state.activeTransTexture));
        if(state!=null&&(entry.name.equals("gbuffers_dan_rim_direct")||entry.name.equals("gbuffers_dan_rim_punctual")))
            for(String name:List.of("depthtex0","dhDepthTex")){Integer unit=entry.units.get(name);if(unit!=null)GL33.glBindSampler(unit,state.bundle.rimDepthSampler);}
    }
    public static void beforeScreen(IrisFrame host,IrisPipeline p,String name,FrameContext frame,Set<Integer> flips){
        State s=STATES.get(p.targets);if(s==null)return;
        if(!s.bundle.eclipse&&name.equals("deferred3")&&s.bundle.scatter!=null){
            try{SCREEN.invoke(host,p,s.bundle.scatter,"deferred",frame,false,flips);}catch(Throwable e){throw failure(e);}
        }
        if(name.equals("composite2")&&!s.actors.isEmpty()&&(!AimohGf2Runtime.enabled(p)||AimohGf2SourceHost.servicesActive)){
            // Character forward writes current colour after the last native flip.
            if(!"false".equalsIgnoreCase(p.configured.values().get("screenspaceReflectionsEnabled")))p.targets.mipmaps(s.bundle.eclipse?3:0);
            try{if(s.bundle.skyBase!=null){SCREEN.invoke(host,p,s.bundle.skyBase,"composite",frame,false,flips);SCREEN.invoke(host,p,s.bundle.skyFilter,"composite",frame,false,flips);}}
            catch(Throwable e){throw failure(e);}
        }
    }
    private static void number(Map<String,float[]> values,JSONObject material,String uniform,String property){values.put(uniform,new float[]{material.getFloat(property)});}
    private static void vector(Map<String,float[]> values,JSONObject material,String uniform,String property){var a=material.getJSONArray(property);values.put(uniform,new float[]{a.getFloat(0),a.getFloat(1),a.getFloat(2)});}

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
        int id,slot,sequence,historyMode;
        final Matrix4f model=new Matrix4f(),previousModel=new Matrix4f(),shadow=new Matrix4f();
        final Vector3f center=new Vector3f();
        final Map<Integer,Integer> transDraws=new LinkedHashMap<>();
        float radius;
    }
    static final class Snapshot {
        final int sequence;final Matrix4f model;
        Snapshot(Actor a){sequence=a.sequence;model=new Matrix4f(a.model);}
    }
    static final class State {
        final Bundle bundle;
        final List<Actor> actors=new ArrayList<>();
        final Map<Integer,Snapshot> previous=new HashMap<>();
        int[] drawMaterial=new int[0],drawHull=new int[0],drawActor=new int[0];
        int width,height,actorCapacity,eye,fringe,motion,shadow,lookup,matrices,eyeFbo,fringeFbo,motionFbo,shadowFbo,atlasSize,tileSize,grid,activeTransTexture,transBackDraws,transFrontDraws;
        ShadowPass frameShadow;ModelPass frameModels;IrisGeometry frameGeometry;Map<String,float[]> frameValues;
        int lookupWidth=512,lookupHeight=1;
        final Vector3f previousEye=new Vector3f();
        boolean cameraHistory;
        long bytes;
        State(Bundle b){bundle=b;}
        int texture(String name){return switch(name){
            case "vpDanEyeDepthTex"->eye;case "vpDanFringeTex"->fringe;case "vpDanMotionTex"->motion;case "vpDanActorShadowTex","vpDanActorShadowCmp"->shadow;
            case "vpDanActorLookup"->lookup;case "vpDanActorMatrices"->matrices;
            case "vpDanTransAlbedo"->activeTransTexture;
            default->-1;};}
        void deleteImages(){
            for(int f:new int[]{eyeFbo,fringeFbo,motionFbo,shadowFbo})if(f!=0)GL30.glDeleteFramebuffers(f);
            for(int t:new int[]{eye,fringe,motion,shadow,lookup,matrices})if(t!=0)GL11.glDeleteTextures(t);
            eye=fringe=motion=shadow=lookup=matrices=eyeFbo=fringeFbo=motionFbo=shadowFbo=0;bytes=0;
        }
        void delete(){deleteImages();previous.clear();actors.clear();}
        void ensure(IrisPipeline p,FrameContext frame,int count,int boneCount){
            int w=count==0?1:frame.width,h=count==0?1:frame.height;
            int atlas=count==0?1:bundle.manifest.optInt("shadow_resolution",2048);
            int rows=Math.max(1,(boneCount+lookupWidth-1)/lookupWidth);
            if(w==width&&h==height&&count<=actorCapacity&&atlas==atlasSize&&rows<=lookupHeight&&eye!=0)return;
            count=Math.max(actorCapacity,Integer.highestOneBit(Math.max(1,count-1))<<1);
            rows=Math.max(lookupHeight,Integer.highestOneBit(Math.max(1,rows-1))<<1);
            deleteImages();width=w;height=h;actorCapacity=count;atlasSize=atlas;lookupHeight=rows;previous.clear();cameraHistory=false;
            for(Actor a:actors){a.historyMode=0;a.previousModel.set(a.model);}
            GL13.glActiveTexture(GL13.GL_TEXTURE0+SCRATCH);
            eye=depth(w,h,GL30.GL_DEPTH_COMPONENT32F);fringe=colour(w,h,GL30.GL_R8,GL11.GL_RED,GL11.GL_UNSIGNED_BYTE);
            motion=colour(w,h,GL30.GL_RG16F,GL30.GL_RG,GL11.GL_FLOAT);shadow=depth(atlas,atlas,GL14.GL_DEPTH_COMPONENT16);
            lookup=colour(lookupWidth,lookupHeight,GL30.GL_R32F,GL11.GL_RED,GL11.GL_FLOAT);
            matrices=colour(9,Math.max(1,count),GL30.GL_RGBA32F,GL11.GL_RGBA,GL11.GL_FLOAT);
            eyeFbo=fbo(0,eye);shadowFbo=fbo(0,shadow);fringeFbo=fbo(fringe,p.targets.depth);motionFbo=fbo(motion,p.targets.depth);
            bytes=(long)w*h*9L+(long)atlas*atlas*2L+(long)lookupWidth*lookupHeight*4L+9L*Math.max(1,count)*16L;
            GL13.glActiveTexture(GL13.GL_TEXTURE0);p.checkPool();
        }
        void collect(IrisPipeline p,FrameContext frame,Map<String,float[]> values){
            ModelDraws d=AimohGf2SourceHost.models.draws();actors.clear();transBackDraws=transFrontDraws=0;Map<Integer,Actor> grouped=new LinkedHashMap<>();Map<Integer,Integer> headers=new LinkedHashMap<>();
            if(drawMaterial.length<d.count){drawMaterial=new int[d.count];drawHull=new int[d.count];drawActor=new int[d.count];}
            Arrays.fill(drawMaterial,-1);Arrays.fill(drawActor,-1);
            int token0=bundle.manifest.getInt("token0"),token1=bundle.manifest.getInt("token1");
            var modelDefinitions=bundle.manifest.optJSONArray("models");
            for(int i=0;i<d.count;i++){
                int palette=d.palette[i],at=palette*12;if(palette<0||at+36>d.bones*12)continue;
                float[] v=d.palettes;if(v[at+15]!=1||v[at+26]!=42137)continue;
                JSONObject definition=bundle.manifest;int materialOffset=0;
                if(modelDefinitions!=null){
                    definition=null;
                    for(int model=0;model<modelDefinitions.length();model++){
                        JSONObject candidate=modelDefinitions.getJSONObject(model);
                        if(v[at+12]==candidate.getInt("token0")&&v[at+13]==candidate.getInt("token1")){definition=candidate;materialOffset=candidate.getInt("material_offset");break;}
                    }
                    if(definition==null)continue;
                }else if(v[at+12]!=token0||v[at+13]!=token1)continue;
                if((d.flags[i]&ModelDraws.COLOUR)==0)continue;
                int id=(int)v[at+24];Actor a=grouped.get(id);
                if(a==null){
                    a=new Actor();a.id=id;a.slot=grouped.size();a.sequence=(int)v[at+25];a.model.set(Arrays.copyOfRange(d.values,i*68,i*68+16));
                    a.center.set(d.values[i*68+24]-frame.eyeX,d.values[i*68+25]-frame.eyeY,d.values[i*68+26]-frame.eyeZ);a.center.x=-a.center.x;a.center.z=-a.center.z;
                    a.radius=Math.max(.05f,d.values[i*68+27]*1.08f);if(!a.center.isFinite()||!Float.isFinite(a.radius))throw new IllegalStateException("DanRP actor bounds are not finite");grouped.put(id,a);
                    Snapshot old=previous.get(id);a.previousModel.set(old==null?a.model:old.model);
                    if(old!=null&&cameraHistory&&!frame.scene.cameraCut){
                        if(a.sequence==old.sequence)a.historyMode=2;
                        else if(a.sequence==old.sequence+1&&v[at+19]>0)a.historyMode=1;
                    }
                }
                headers.put(palette,a.slot);
                if(d.meshes[i]!=null&&d.meshes[i].getPath()!=null){
                    String path=d.meshes[i].getPath().getPath(),prefix="aimoh:pmx-part-";
                    if(path.startsWith(prefix))try{
                        int ordinal=Integer.parseInt(path.substring(prefix.length()));
                        var map=definition.getJSONArray("submesh_materials");
                        int hull=ordinal==20000?2:ordinal>=10000?1:0;
                        int part=ordinal-hull*10000;
                        if(hull==2){drawMaterial[i]=materialOffset;drawHull[i]=2;drawActor[i]=a.slot;}
                        else if(part>=0&&part<map.length()){drawMaterial[i]=map.getInt(part)+materialOffset;drawHull[i]=hull;drawActor[i]=a.slot;}
                        if(ordinal>=0&&ordinal<map.length())a.transDraws.put(map.getInt(ordinal)+materialOffset,i);
                    }catch(NumberFormatException ignored){}
                }
            }
            actors.addAll(grouped.values());ensure(p,frame,actors.size(),d.bones);
            grid=Math.max(1,(int)Math.ceil(Math.sqrt(actors.size())));tileSize=Math.max(1,atlasSize/grid);
            float[] dir=values.get("lightDir");if(dir==null||dir.length<3)throw new IllegalStateException("DanRP needs the active backend lightDir input");
            Vector3f light=new Vector3f(dir[0],dir[1],dir[2]).normalize();
            for(Actor a:actors){
                Vector3f top=Math.abs(light.y)>.98f?new Vector3f(0,0,1):new Vector3f(0,1,0);
                Vector3f eye=new Vector3f(light).mul(2*a.radius).add(a.center);
                a.shadow.setOrtho(-a.radius,a.radius,-a.radius,a.radius,a.radius*.5f,a.radius*3.5f).lookAt(eye,a.center,top);
            }
            float[] map=new float[lookupWidth*lookupHeight];Arrays.fill(map,-1);for(var e:headers.entrySet())map[e.getKey()]=e.getValue();
            float[] data=new float[9*4*Math.max(1,actors.size())];
            for(Actor a:actors){int off=a.slot*36;a.shadow.get(data,off);a.previousModel.get(data,off+16);data[off+32]=a.historyMode;data[off+33]=a.id;data[off+34]=a.sequence;data[off+35]=a.radius;}
            upload(lookup,lookupWidth,lookupHeight,GL11.GL_RED,map);upload(matrices,9,Math.max(1,actors.size()),GL11.GL_RGBA,data);
            values.put("vpDanActorCount",new float[]{actors.size()});values.put("vpDanShadowTile",new float[]{atlasSize,tileSize,grid,1f/atlasSize});
            values.put("vpDanHistoryReset",new float[]{frame.scene.cameraCut||!cameraHistory?1:0});
            values.put("vpDanPreviousEyeNative",new float[]{previousEye.x,previousEye.y,previousEye.z});
            values.put("uSceneOrigin",new float[]{(float)frame.scene.originX,(float)frame.scene.originY,(float)frame.scene.originZ});
            SceneData scene=frame.scene;values.put("uDaylight",new float[]{scene.daylightKnown?scene.daylightR:.8f,scene.daylightKnown?scene.daylightG:.8f,scene.daylightKnown?scene.daylightB:.8f});
        }
        void finish(FrameContext frame){previous.clear();for(Actor a:actors)previous.put(a.id,new Snapshot(a));previousEye.set(frame.eyeX,frame.eyeY,frame.eyeZ);cameraHistory=true;}
    }
    private static int colour(int w,int h,int format,int pixels,int type){int t=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,t);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,format,w,h,0,pixels,type,(ByteBuffer)null);parameters();return t;}
    private static int depth(int w,int h,int format){int t=colour(w,h,format,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL14.GL_TEXTURE_COMPARE_MODE,GL11.GL_NONE);return t;}
    private static void parameters(){GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);}
    private static int fbo(int colour,int depth){int f=GL30.glGenFramebuffers();GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,f);if(colour!=0)GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,colour,0);if(depth!=0)GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL11.GL_TEXTURE_2D,depth,0);GL11.glDrawBuffer(colour==0?GL11.GL_NONE:GL30.GL_COLOR_ATTACHMENT0);GL11.glReadBuffer(colour==0?GL11.GL_NONE:GL30.GL_COLOR_ATTACHMENT0);if(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("DanRP framebuffer incomplete");return f;}
    private static void upload(int texture,int w,int h,int format,float[] data){GL13.glActiveTexture(GL13.GL_TEXTURE0+SCRATCH);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,w,h,format,GL11.GL_FLOAT,data);GL13.glActiveTexture(GL13.GL_TEXTURE0);}

    // Same exclusions as each fragment shader, before submitting geometry to the GPU.
    static boolean takeDraw(State s,String stage,int i,int actor,int material){
        if(i<0||i>=s.drawMaterial.length||s.drawMaterial[i]<0)return false;
        int h=s.drawHull[i],m=s.drawMaterial[i],flags=s.bundle.materialFlags[m];
        if(stage.equals("fringe"))return h==2;
        if(stage.equals("outline"))return h==1&&(flags&16)==0;
        if(h!=0)return false;
        if(stage.equals("actor_shadow"))return s.drawActor[i]==actor&&(flags&12)==0;
        if(stage.equals("eye_depth"))return (flags&2)!=0;
        if(stage.equals("eye_blend"))return m==material;
        if(stage.equals("eyelash")||stage.equals("trans")||stage.equals("trans_motion"))return m==material&&s.drawActor[i]==actor;
        if(stage.equals("motion"))return (flags&20)==0;
        if((flags&28)!=0)return false;
        if(stage.equals("forward"))return (flags&1)==0;
        if(stage.equals("forward_face"))return (flags&1)!=0;
        return true;
    }

    private static void draw(IrisPipeline p,FrameContext frame,ShadowPass shadow,ModelPass models,IrisGeometry geometry,Map<String,float[]> values,IrisProgram entry,boolean depthWrite,int depthFunc,int blend){
        if(AimohGf2Runtime.enabled(p))return; // isolated original-asset reference only

        State selection=STATES.get(p.targets);String stage=entry.name.substring("gbuffers_dan_".length());
        String actorKey=stage.equals("actor_shadow")?"vpDanRenderActor":stage.equals("eyelash")?"vpDanLayerActor":"vpDanTransActor";
        String materialKey=stage.equals("eye_blend")?"vpDanEyeMaterial":stage.equals("eyelash")?"vpDanLayerMaterial":"vpDanTransMaterial";
        int actor=values.containsKey(actorKey)?(int)values.get(actorKey)[0]:-1,material=values.containsKey(materialKey)?(int)values.get(materialKey)[0]:-1;
        // Old packs retain all matching parts until they provide semantic role data.
        scopedDrawFilter=selection.bundle.manifest.has("draw_roles")?i->takeDraw(selection,stage,i,actor,material):i->i>=0&&i<selection.drawMaterial.length&&selection.drawMaterial[i]>=0;
        try {
            ENTITIES.invoke(geometry,p,values,frame,shadow,entry,false,(Runnable)()->{
                GL11.glDepthMask(depthWrite);GL11.glDepthFunc(depthFunc);
                if(blend==0)GL11.glDisable(GL11.GL_BLEND);
                else {
                    GL11.glEnable(GL11.GL_BLEND);
                    if(blend==-3)GL14.glBlendFuncSeparate(GL11.GL_DST_COLOR,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ZERO,GL11.GL_ONE);
                    else if(blend==-4)GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ZERO,GL11.GL_ONE);
                    else if(blend==-5)GL14.glBlendFuncSeparate(GL11.GL_ONE,GL11.GL_ONE,GL11.GL_ZERO,GL11.GL_ONE);
                    else if(blend==-2)GL14.glBlendFuncSeparate(GL11.GL_DST_COLOR,GL11.GL_ZERO,GL11.GL_ZERO,GL11.GL_ONE);
                    else GL11.glBlendFunc(blend==-1?GL11.GL_ONE:GL11.GL_SRC_ALPHA,blend==-1?GL11.GL_ONE:GL11.GL_ONE_MINUS_SRC_ALPHA);
                    GL20.glBlendEquationSeparate(blend<0?GL14.GL_FUNC_ADD:blend,GL14.GL_FUNC_ADD);
                }
                scopedDepth=true;stageDepthWrite=depthWrite;stageDepthFunc=depthFunc;
                try{models.iris(entry.gl,frame.viewProjection,0,false);}finally{scopedDepth=false;}
            });
        }catch(Throwable e){throw failure(e);}finally{scopedDrawFilter=null;GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD,GL14.GL_FUNC_ADD);GL11.glDisable(GL11.GL_BLEND);GL11.glDepthMask(true);GL11.glDepthFunc(GL11.GL_LEQUAL);}
    }
    public static void producers(IrisPipeline p,FrameContext frame,ShadowPass shadow,ModelPass models,IrisGeometry geometry,Map<String,float[]> values){
        if(AimohGf2Runtime.enabled(p))return;
        Bundle b=BUNDLES.get(p.programs);if(b==null)return;
        int oldDraw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),oldRead=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        State s=STATES.computeIfAbsent(p.targets,k->new State(b));
        try {
            if(b.eclipse&&!s.actors.isEmpty()){
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,p.targets.framebuffer(List.of(1,8,2,17),false,1,b.stages.get("gbuffer").tintOutput));
                GL11.glViewport(0,0,frame.width,frame.height);
                draw(p,frame,shadow,models,geometry,values,b.stages.get("gbuffer"),true,GL11.GL_LEQUAL,0);
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,s.eyeFbo);GL11.glViewport(0,0,s.width,s.height);GL11.glDepthMask(true);GL11.glClearDepth(1);GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            if(!s.actors.isEmpty())draw(p,frame,shadow,models,geometry,values,b.stages.get("eye_depth"),true,GL11.GL_LEQUAL,0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,s.shadowFbo);GL11.glViewport(0,0,s.atlasSize,s.atlasSize);GL11.glDepthMask(true);GL11.glClearDepth(1);GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            boolean previousOffset=GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL);float previousFactor=GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR),previousUnits=GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
            GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);GL11.glPolygonOffset(2.5f,1.0f); // Source SetGlobalDepthBias(1,2.5), mapped to GL slope/units.
            try{for(Actor a:s.actors){values.put("vpDanRenderActor",new float[]{a.slot});values.put("vpDanShadowProjection",a.shadow.get(new float[16]));GL11.glViewport((a.slot%s.grid)*s.tileSize,(a.slot/s.grid)*s.tileSize,s.tileSize,s.tileSize);draw(p,frame,shadow,models,geometry,values,b.stages.get("actor_shadow"),true,GL11.GL_LEQUAL,0);}}
            finally{GL11.glPolygonOffset(previousFactor,previousUnits);if(!previousOffset)GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);}
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,s.fringeFbo);GL11.glViewport(0,0,s.width,s.height);GL11.glColorMask(true,true,true,true);GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{0,0,0,0});
            if(!s.actors.isEmpty())draw(p,frame,shadow,models,geometry,values,b.stages.get("fringe"),false,GL11.GL_LEQUAL,0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,s.motionFbo);GL11.glViewport(0,0,s.width,s.height);GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{65504,65504,0,0});
            if(!s.actors.isEmpty())draw(p,frame,shadow,models,geometry,values,b.stages.get("motion"),false,GL11.GL_LEQUAL,0);
        }finally{GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,oldDraw);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,oldRead);GL11.glViewport(0,0,frame.width,frame.height);}
    }

    private static void eclipseDirection(IrisPipeline p,Bundle b,Map<String,float[]> values){
        boolean day=values.get("sunElevation")[0]>1e-5f;
        Vector3f direction;
        if(!day&&"true".equals(p.configured.values().get("CUSTOM_MOON_ROTATION"))){
            // begin.csh produces this vector before model/PerObject drawing.
            // Reflect its actual layout once; do not assume a constant SSBO offset.
            if(b.moonDirectionOffset<0){
                search:for(var passes:p.programs.passes.values())for(var pass:passes){
                    List<IrisProgram> entries=new ArrayList<>(pass.computes());if(pass.program()!=null)entries.add(pass.program());
                    for(IrisProgram entry:entries)for(String name:List.of("customMoonVecSSBO","SSBO1.customMoonVecSSBO")){
                        int index=GL43.glGetProgramResourceIndex(entry.gl.id(),GL43.GL_BUFFER_VARIABLE,name);if(index==-1)continue;
                        int[] offset=new int[1];GL43.glGetProgramResourceiv(entry.gl.id(),GL43.GL_BUFFER_VARIABLE,index,new int[]{GL43.GL_OFFSET},null,offset);
                        b.moonDirectionOffset=offset[0];break search;
                    }
                }
            }
            Integer buffer=p.images.buffers.get(4);
            if(buffer==null||b.moonDirectionOffset<0)throw new IllegalStateException("Eclipse GPU moon direction provider missing");
            var vector=BufferUtils.createFloatBuffer(3);GL45.glGetNamedBufferSubData(buffer,b.moonDirectionOffset,vector);
            direction=new Vector3f(vector.get(0),vector.get(1),vector.get(2));
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
    public static void prepare(IrisPipeline p,FrameContext frame,ShadowPass shadow,ModelPass models,IrisGeometry geometry,Map<String,float[]> values){
        Bundle b=BUNDLES.get(p.programs);if(b==null)return;
        int oldDraw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),oldRead=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try{if(AimohGf2Runtime.enabled(p))AimohGf2SourceHost.prepare(p,frame,shadow,models,values);if(b.eclipse)eclipseDirection(p,b,values);State s=STATES.computeIfAbsent(p.targets,k->new State(b));s.collect(p,frame,values);if(b.eclipse)b.nativeState=s;}
        finally{GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,oldDraw);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,oldRead);}
    }
    public static void forward(IrisPipeline p,FrameContext frame,ShadowPass shadow,ModelPass models,IrisGeometry geometry,Map<String,float[]> values){
        if(AimohGf2Runtime.enabled(p))return;
        State s=STATES.get(p.targets);if(s==null)return;
        s.frameShadow=shadow;s.frameModels=models;s.frameGeometry=geometry;s.frameValues=values;
        if(s.bundle.eclipse)return;
        forwardNow(p,frame,shadow,models,geometry,values);
    }
    private static void forwardNow(IrisPipeline p,FrameContext frame,ShadowPass shadow,ModelPass models,IrisGeometry geometry,Map<String,float[]> values){
        State s=STATES.get(p.targets);if(s==null)return;
        if(s.actors.isEmpty()){s.finish(frame);return;}
        int oldDraw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),oldRead=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,p.targets.framebuffer(List.of(s.bundle.eclipse?3:0),false,1,-1));GL11.glViewport(0,0,frame.width,frame.height);
            draw(p,frame,shadow,models,geometry,values,s.bundle.stages.get("forward"),false,GL11.GL_LEQUAL,0);
            draw(p,frame,shadow,models,geometry,values,s.bundle.stages.get("forward_face"),false,GL11.GL_LEQUAL,0);
            draw(p,frame,shadow,models,geometry,values,s.bundle.stages.get("indirect"),false,GL11.GL_LEQUAL,-1);
            draw(p,frame,shadow,models,geometry,values,s.bundle.stages.get("rim_direct"),false,GL11.GL_LEQUAL,-1);
            draw(p,frame,shadow,models,geometry,values,s.bundle.stages.get("rim_punctual"),false,GL11.GL_LEQUAL,-1);
            var eyeLayers=s.bundle.manifest.getJSONArray("eye_blend_materials");
            for(int i=0;i<eyeLayers.length();i++){
                JSONObject layer=eyeLayers.getJSONObject(i);int material=layer.getInt("material");
                if(s.actors.stream().noneMatch(actor->actor.transDraws.containsKey(material)))continue;
                values.put("vpDanEyeMaterial",new float[]{material});number(values,layer,"vpDanEyeAlpha","alpha");vector(values,layer,"vpDanEyeColor","color");
                draw(p,frame,shadow,models,geometry,values,s.bundle.stages.get("eye_blend"),false,GL11.GL_LEQUAL,layer.getString("blend").equals("ADD")?-5:-2);
            }
            Texture[] drawn=(Texture[])DRAWN.get(SOURCES.get(models));
            var lashes=s.bundle.manifest.getJSONArray("eyelash_materials");
            for(Actor actor:s.actors)for(int i=0;i<lashes.length();i++){
                int material=lashes.getInt(i);Integer index=actor.transDraws.get(material);if(index==null)continue;
                Texture tex=drawn[index];if(tex==null)throw new IllegalStateException("DanRP outer-lash texture missing: "+material);
                tex.bind();s.activeTransTexture=tex.getID();if(s.activeTransTexture<=0)throw new IllegalStateException("DanRP outer-lash texture unavailable");
                values.put("vpDanLayerMaterial",new float[]{material});values.put("vpDanLayerActor",new float[]{actor.slot});
                draw(p,frame,shadow,models,geometry,values,s.bundle.stages.get("eyelash"),false,GL11.GL_ALWAYS,-4);
            }
            s.activeTransTexture=0;
            if(!s.bundle.eclipse){drawOutline(p,frame,s);s.finish(frame);}
        }catch(ReflectiveOperationException e){throw failure(e);}
        finally{s.activeTransTexture=0;GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,oldDraw);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,oldRead);GL11.glViewport(0,0,frame.width,frame.height);}
    }
    private static void drawOutline(IrisPipeline p,FrameContext frame,State s){
        int outlineFbo=p.targets.framebuffer(s.bundle.eclipse?List.of(3,1,8,2,17):List.of(0,1,2,4),false,1,-1);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,outlineFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,(s.bundle.eclipse?GL30.GL_COLOR_ATTACHMENT5:GL30.GL_COLOR_ATTACHMENT4),GL11.GL_TEXTURE_2D,s.motion,0);
        GL20.glDrawBuffers(s.bundle.eclipse?new int[]{GL30.GL_COLOR_ATTACHMENT0,GL30.GL_COLOR_ATTACHMENT1,GL30.GL_COLOR_ATTACHMENT2,GL30.GL_COLOR_ATTACHMENT3,GL30.GL_COLOR_ATTACHMENT4,GL30.GL_COLOR_ATTACHMENT5}:new int[]{GL30.GL_COLOR_ATTACHMENT0,GL30.GL_COLOR_ATTACHMENT1,GL30.GL_COLOR_ATTACHMENT2,GL30.GL_COLOR_ATTACHMENT3,GL30.GL_COLOR_ATTACHMENT4});
        try{draw(p,frame,s.frameShadow,s.frameModels,s.frameGeometry,s.frameValues,s.bundle.stages.get("outline"),true,GL11.GL_LEQUAL,0);}
        finally{GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,(s.bundle.eclipse?GL30.GL_COLOR_ATTACHMENT5:GL30.GL_COLOR_ATTACHMENT4),GL11.GL_TEXTURE_2D,0,0);GL20.glDrawBuffers(s.bundle.eclipse?new int[]{GL30.GL_COLOR_ATTACHMENT0,GL30.GL_COLOR_ATTACHMENT1,GL30.GL_COLOR_ATTACHMENT2,GL30.GL_COLOR_ATTACHMENT3,GL30.GL_COLOR_ATTACHMENT4}:new int[]{GL30.GL_COLOR_ATTACHMENT0,GL30.GL_COLOR_ATTACHMENT1,GL30.GL_COLOR_ATTACHMENT2,GL30.GL_COLOR_ATTACHMENT3});}
        p.targets.copyDepth(p.targets.depth,p.targets.depthOpaque);p.targets.copyDepth(p.targets.depth,p.targets.depthNoHand);
    }
    public static void afterScreen(IrisPipeline p,String name,FrameContext frame){
        if(AimohGf2Runtime.enabled(p)){if(AimohGf2SourceHost.servicesActive){State source=STATES.get(p.targets);if(source!=null)source.finish(frame);}return;}

        State s=STATES.get(p.targets);
        if(s==null)return;
        if(!s.bundle.eclipse){transparentAfterScreen(p,name,frame);return;}
        if(!name.equals("composite2"))return;
        if(s.frameModels==null||s.frameValues==null)throw new IllegalStateException("Eclipse character phase has no frozen current frame");
        int oldDraw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),oldRead=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        boolean depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),write=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE),blend=GL11.glIsEnabled(GL11.GL_BLEND);
        int depthFunc=GL11.glGetInteger(GL11.GL_DEPTH_FUNC),cullFace=GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
        int[] viewport=new int[4];GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);
        int srcRgb=GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),dstRgb=GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),srcA=GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),dstA=GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int eqRgb=GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB),eqA=GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        try{
            // Source CharacterForwardLighting.cs: Forward -> Transparent -> Outline.
            // Eclipse provides its world lighting and reflection first, then consumes
            // this HDR colour in its existing fog, history and post passes.
            forwardNow(p,frame,s.frameShadow,s.frameModels,s.frameGeometry,s.frameValues);
            transparentAfterScreen(p,name,frame);
            if(!s.actors.isEmpty())drawOutline(p,frame,s);
            s.finish(frame);
        }finally{
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,oldDraw);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,oldRead);
            GL11.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);GL11.glDepthMask(write);GL11.glDepthFunc(depthFunc);GL11.glCullFace(cullFace);
            GL14.glBlendFuncSeparate(srcRgb,dstRgb,srcA,dstA);GL20.glBlendEquationSeparate(eqRgb,eqA);
            if(depth)GL11.glEnable(GL11.GL_DEPTH_TEST);else GL11.glDisable(GL11.GL_DEPTH_TEST);
            if(cull)GL11.glEnable(GL11.GL_CULL_FACE);else GL11.glDisable(GL11.GL_CULL_FACE);
            if(blend)GL11.glEnable(GL11.GL_BLEND);else GL11.glDisable(GL11.GL_BLEND);
        }
    }
    private static void transparentAfterScreen(IrisPipeline p,String name,FrameContext frame){
        State s=STATES.get(p.targets);if(s==null||!name.equals("composite2"))return;
        var config=s.bundle.manifest.getJSONArray("transparent_materials");if(config.isEmpty()||s.actors.isEmpty())return;
        if(s.frameModels==null||s.frameValues==null)throw new IllegalStateException("DanRP transparent stage has no current frame");
        int oldDraw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),oldRead=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        boolean depthWasEnabled=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),depthWasWritable=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),cullWasEnabled=GL11.glIsEnabled(GL11.GL_CULL_FACE),blendWasEnabled=GL11.glIsEnabled(GL11.GL_BLEND);
        int oldDepthFunc=GL11.glGetInteger(GL11.GL_DEPTH_FUNC),oldCullFace=GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);int[] oldViewport=new int[4];GL11.glGetIntegerv(GL11.GL_VIEWPORT,oldViewport);
        int srcRgb=GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),dstRgb=GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),srcAlpha=GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),dstAlpha=GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int eqRgb=GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB),eqAlpha=GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        try{
            Texture[] drawn=(Texture[])DRAWN.get(SOURCES.get(s.frameModels));
            List<Actor> ordered=new ArrayList<>(s.actors);ordered.sort(Comparator.comparingDouble((Actor a)->a.center.lengthSquared()).reversed());
            for(Actor actor:ordered)for(int n=0;n<config.length();n++){
                JSONObject material=config.getJSONObject(n);int id=material.getInt("material");Integer index=actor.transDraws.get(id);
                if(index==null)continue;Texture tex=drawn[index];if(tex==null)throw new IllegalStateException("DanRP transparent material has no bound texture: "+id);
                tex.bind();s.activeTransTexture=tex.getID();if(s.activeTransTexture<=0)throw new IllegalStateException("DanRP transparent texture unavailable: "+id);
                Map<String,float[]> v=s.frameValues;v.put("vpDanTransMaterial",new float[]{id});v.put("vpDanTransActor",new float[]{actor.slot});
                v.put("vpDanTransAlpha",new float[]{material.getFloat("alpha")});v.put("vpDanTransBackAlpha",new float[]{material.getFloat("backface_alpha")});
                v.put("vpDanTransFogMode",new float[]{material.getString("fog_mode").equals("modulate")?1:0});
                v.put("vpDanTransRamp",new float[]{material.getBoolean("shadow_ramp")?1:0});
                vector(v,material,"vpDanTransSelfLight","self_light");vector(v,material,"vpDanTransShadowColor","shadow_color");vector(v,material,"vpDanTransSelfEnvColor","self_env_color");
                number(v,material,"vpDanTransMainLightLerp","main_light_lerp");number(v,material,"vpDanTransDirectOcclusion","direct_occlusion");
                number(v,material,"vpDanTransShadowOffset","shadow_offset");number(v,material,"vpDanTransShadowNdotL","shadow_smooth_ndotl");number(v,material,"vpDanTransShadowScene","shadow_smooth_scene");number(v,material,"vpDanTransShadowStrength","shadow_strength");
                number(v,material,"vpDanTransEnvLerp","env_color_lerp");number(v,material,"vpDanTransIndirUp","indir_diff_up");number(v,material,"vpDanTransIndirDiff","indir_diff_intensity");number(v,material,"vpDanTransIndirSpec","indir_spec_intensity");
                for(int side=material.getBoolean("backface_enabled")?1:0;side>=0;side--){
                    v.put("vpDanTransSide",new float[]{side});String cull=material.getString("cull");
                    scopedCull=side==1?GL11.GL_FRONT:cull.equals("OFF")?0:cull.equals("FRONT")?GL11.GL_FRONT:GL11.GL_BACK;
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,p.targets.framebuffer(List.of(s.bundle.eclipse?3:0),false,1,-1));GL11.glViewport(0,0,frame.width,frame.height);
                    draw(p,frame,s.frameShadow,s.frameModels,s.frameGeometry,v,s.bundle.stages.get("trans"),material.getBoolean("z_write"),GL11.GL_LEQUAL,material.getString("blend").equals("ALPHA")?-4:-3);
                    if(side==1)s.transBackDraws++;else s.transFrontDraws++;
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,s.motionFbo);
                    draw(p,frame,s.frameShadow,s.frameModels,s.frameGeometry,v,s.bundle.stages.get("trans_motion"),false,GL11.GL_LEQUAL,0);
                }
            }
        }catch(ReflectiveOperationException e){throw failure(e);}
        finally{
            scopedCull=-1;s.activeTransTexture=0;GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,oldDraw);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,oldRead);
            GL11.glViewport(oldViewport[0],oldViewport[1],oldViewport[2],oldViewport[3]);GL11.glDepthMask(depthWasWritable);GL11.glDepthFunc(oldDepthFunc);GL11.glCullFace(oldCullFace);
            GL14.glBlendFuncSeparate(srcRgb,dstRgb,srcAlpha,dstAlpha);GL20.glBlendEquationSeparate(eqRgb,eqAlpha);
            if(depthWasEnabled)GL11.glEnable(GL11.GL_DEPTH_TEST);else GL11.glDisable(GL11.GL_DEPTH_TEST);
            if(cullWasEnabled)GL11.glEnable(GL11.GL_CULL_FACE);else GL11.glDisable(GL11.GL_CULL_FACE);
            if(blendWasEnabled)GL11.glEnable(GL11.GL_BLEND);else GL11.glDisable(GL11.GL_BLEND);
        }
    }
}
