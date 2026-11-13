package aimoh.pmxpreview;

import aimoh.motion.PmxDrawMesh;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;
import org.joml.Matrix4f;
import org.json.JSONObject;
import org.json.JSONArray;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.j2se.J2SEPlatform;
import zombie.ZomboidFileSystem;
import org.lwjgl.opengl.*;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;

/** Displays world snapshots and isolated, single-pose outfit previews. */
public class ReadyRoomPreview {
    private static final Object motionPathsLock=new Object();
    private static final class BrowsePreview {
        final String id;
        final ModelSession session;
        ModelSession appearanceSource;
        long appearanceRevision=Long.MIN_VALUE;
        long poseFrame;

        BrowsePreview(String id,ModelSession session) {
            this.id=id;
            this.session=session;
        }

        void update(ModelSession equipped, AnimationPlayer worldPlayer) {
            long revision=equipped==null?0:equipped.model.appearance().revision();
            if(poseFrame!=0&&equipped==appearanceSource&&revision==appearanceRevision)return;
            if(equipped!=null) {
                var appearance=new JSONObject(equipped.model.appearance().stateJson());
                session.applySettings(new JSONObject().put("appearance",appearance)
                    .put("appearance_controls",equipped.profile.optJSONObject("appearance_controls")),false);
                session.model.appearance().loadExpression(equipped.model.appearance().expressionSnapshot());
            }
            // Sample the game's standing idle on a separate player. The live tracks can be
            // holding a weapon, crouching, or still blending during the first render.
            var idle=AnimationPlayer.alloc(worldPlayer.getModel());
            try {
                var track=idle.play("Bob_Idle",true);
                if(track==null)return;
                track.setBlendWeight(1);
                track.setCurrentTimeValue(track.getDuration()*.25f);
                idle.Update(0);
                session.update(poseFrame++,System.nanoTime(),idle,false,0,0,0,false,true,false,null,false);
            } finally {
                idle.release();
            }
            appearanceSource=equipped;
            appearanceRevision=revision;
        }
    }
    private static final IdentityHashMap<IsoGameCharacter,BrowsePreview> browsePreviews=new IdentityHashMap<>();
    private static final String SAMPLE_PREFIX="sample:";
    private static Path library() {
        return Path.of(ZomboidFileSystem.instance.getCacheDir(),"R116","Motions").toAbsolutePath().normalize();
    }
    public static String motionDirectory() { return library().toString(); }
    private static boolean motionFile(Path path) {
        return path.getFileName()!=null&&path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".vmd")
            &&Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS);
    }
    private static Path sampleLibrary() {
        String directory=ZomboidFileSystem.instance.getModDir(Main.MOD_ID);
        if(directory==null)throw new IllegalStateException("无法定位 R116 示例动作");
        Path mod=Path.of(directory).toAbsolutePath().normalize();
        Path relative=Path.of("media/aimoh_pmx/motions");
        return Files.isDirectory(mod.resolve(relative))?mod.resolve(relative):mod.resolve("42").resolve(relative);
    }
    private static List<String> motionNames(Path directory) throws IOException {
        if(!Files.isDirectory(directory))return List.of();
        try(var paths=Files.list(directory)){
            return paths.filter(ReadyRoomPreview::motionFile).map(p->p.getFileName().toString())
                .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder())).toList();
        }
    }
    private static Path namedMotion(Path directory,String name) {
        if(name.isBlank()||name.indexOf('/')>=0||name.indexOf('\\')>=0||name.indexOf(':')>=0)
            throw new IllegalArgumentException("动作文件名无效");
        Path file=directory.resolve(name).normalize();
        if(!directory.equals(file.getParent()))throw new IllegalArgumentException("动作文件名无效");
        return file;
    }
    private static Path localMotionPath(String value) {
        String text=value==null?"":value.strip();
        if(text.length()>=2&&text.startsWith("\"")&&text.endsWith("\""))text=text.substring(1,text.length()-1);
        if(text.startsWith("\\\\")||text.startsWith("//"))throw new IllegalArgumentException("请输入本机磁盘上的 VMD 路径");
        Path path=Path.of(text);
        if(!path.isAbsolute())throw new IllegalArgumentException("请粘贴完整的 VMD 文件路径");
        return path.normalize();
    }
    private static LinkedHashSet<Path> savedMotionPaths() throws IOException {
        Path index=library().resolve("local-paths.json");
        var paths=new LinkedHashSet<Path>();
        if(Files.exists(index)){
            var data=new JSONArray(Files.readString(index));
            for(int i=0;i<data.length();i++)paths.add(localMotionPath(data.getString(i)));
        }
        return paths;
    }
    private static void saveMotionPaths(LinkedHashSet<Path> paths) throws IOException {
        Path dir=Files.createDirectories(library());
        Path staged=Files.createTempFile(dir,"local-paths-",".tmp");
        try {
            var data=new JSONArray();
            paths.forEach(path->data.put(path.toString()));
            Files.writeString(staged,data.toString(2));
            Files.move(staged,dir.resolve("local-paths.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(staged);}
    }
    public static KahluaTable addMotionPath(String value) {
        var result=new JSONObject();
        try {
            Path path=localMotionPath(value);
            if(!Files.isDirectory(path))throw new IOException("请输入存在的本地文件夹路径");
            synchronized(motionPathsLock){
                var paths=savedMotionPaths();
                if(paths.add(path))saveMotionPaths(paths);
            }
            result.put("path",path.toString());
        }catch(Exception e){result.put("error",e.getMessage()==null?"保存动作路径失败":e.getMessage());}
        return (KahluaTable)lua(result);
    }
    public static String removeMotionPath(String value) {
        try {
            Path path=localMotionPath(value);
            synchronized(motionPathsLock){
                var paths=savedMotionPaths();
                if(!paths.remove(path))return "这个来源没有登记";
                saveMotionPaths(paths);
            }
            return "";
        }catch(Exception e){return e.getMessage()==null?"移除动作路径失败":e.getMessage();}
    }
    public static KahluaTable motionLibrary() {
        var entries=new LinkedHashSet<String>();
        try {
            List<String> local=motionNames(Files.createDirectories(library()));
            for(String sample:motionNames(sampleLibrary()))
                if(local.stream().noneMatch(sample::equalsIgnoreCase))entries.add(SAMPLE_PREFIX+sample);
            entries.addAll(local);
        }catch(Exception e){Main.error(e);}
        try {
            for(Path source:savedMotionPaths()) {
                if(motionFile(source))entries.add(source.toString()); // Paths saved by older releases.
                else {
                    try {
                        for(String name:motionNames(source))entries.add(source.resolve(name).toString());
                    }catch(IOException e){Main.error(e);}
                }
            }
        }catch(Exception e){Main.error(e);}
        return (KahluaTable)lua(new JSONArray(entries));
    }
    static boolean motionAllowed(IsoPlayer actor) {
        return !actor.isDead()&&actor.getVehicle()==null&&!actor.isPlayerMoving()&&!actor.isAiming()&&!actor.isAttackStarted();
    }
    public static String gripFamily(IsoGameCharacter actor) {
        return Main.isReady(actor)?GripCalibration.itemKey(actor):"";
    }
    public static String gripItemName(IsoGameCharacter actor) {
        var item=GripCalibration.heldItem(actor);
        return item==null?"":item.getDisplayName();
    }
    public static KahluaTable gripValues(IsoGameCharacter actor,String key) {
        ModelSession session=Main.session(actor);
        if(session==null||key==null||key.isEmpty()||!key.equals(gripFamily(actor)))
            return J2SEPlatform.getInstance().newTable();
        return GripCalibration.sliders(actor,session.entry.id(),key);
    }
    public static boolean gripSet(IsoGameCharacter actor,String key,int axis,double value) {
        ModelSession session=Main.session(actor);
        return session!=null&&Main.isReady(actor)&&GripCalibration.set(actor,session.entry.id(),key,axis,value);
    }
    private static ModelSession expressionSession(IsoGameCharacter actor, String id) {
        if (!(actor instanceof IsoPlayer player) || !player.isLocalPlayer() || !Main.isReady(actor)) return null;
        ModelSession session = Main.session(actor);
        return session != null && session.entry.id().equals(id) ? session : null;
    }
    public static KahluaTable expressionRows(IsoGameCharacter actor, String id) {
        ModelSession s = expressionSession(actor, id);
        if (s != null) synchronized (s) {
            if (!s.retired && !s.failed) return (KahluaTable)lua(s.model.appearance().expressionRows());
        }
        return J2SEPlatform.getInstance().newTable();
    }
    public static String expressionSnapshot(IsoGameCharacter actor, String id) {
        ModelSession s = expressionSession(actor, id);
        if (s != null) synchronized (s) {
            if (!s.retired && !s.failed) return s.model.appearance().expressionSnapshot();
        }
        return "";
    }
    public static String expressionSet(IsoGameCharacter actor, String id, int index, double value) {
        ModelSession s = expressionSession(actor, id);
        if (s == null) return "请先启用一个 R116 人物";
        synchronized (s) {
            if (s.retired || s.failed) return "人物正在切换";
            try { s.model.appearance().setExpression(index, value); return ""; }
            catch (RuntimeException e) { return e.toString(); }
        }
    }
    public static String expressionLoad(IsoGameCharacter actor, String id, String preset) {
        ModelSession s = expressionSession(actor, id);
        if (s == null) return "请先启用一个 R116 人物";
        synchronized (s) {
            if (s.retired || s.failed) return "人物正在切换";
            try { s.model.appearance().loadExpression(preset); return ""; }
            catch (RuntimeException e) { return e.toString(); }
        }
    }
    public static KahluaTable motionState(IsoGameCharacter actor) {
        ModelSession s=Main.session(actor);
        return (KahluaTable)lua(s==null
            ?new JSONObject().put("name","").put("paused",false).put("physics",false).put("frame",0).put("endFrame",0).put("speed",1).put("revision","")
            :s.model.motionTimeline());
    }
    public static String motionCommand(IsoGameCharacter actor,String command,String name) {
        if(!(actor instanceof IsoPlayer player)||!player.isLocalPlayer())return "只可控制本机玩家的动作";
        ModelSession s=Main.session(actor);
        if(s==null||!Main.isReady(actor))return "请先启用一个 R116 人物";
        synchronized(s) {
            if(s.retired||s.failed)return "人物正在切换";
            try {
                switch(command) {
                    case "play" -> {
                        if(!motionAllowed(player))return "请先停下移动、瞄准或下车";
                        if(name==null||name.isBlank())return "请先选一个动作";
                        Path file;
                        if(name.startsWith(SAMPLE_PREFIX)){
                            file=namedMotion(sampleLibrary(),name.substring(SAMPLE_PREFIX.length()));
                        }else if(Path.of(name).isAbsolute()){
                            file=localMotionPath(name);
                            var sources=savedMotionPaths();
                            if(!sources.contains(file)&&!sources.contains(file.getParent()))
                                return "请先添加动作所在的文件夹";
                        }else{
                            file=namedMotion(library(),name);
                        }
                        if(!motionFile(file))return "动作文件已移动或移除";
                        s.model.playMotion(file);
                    }
                    case "seek" -> {
                        if(!motionAllowed(player))return "请先停下移动、瞄准或下车";
                        String[] target=name==null?new String[0]:name.split(":",2);
                        if(target.length!=2||!s.model.seekMotion(Long.parseLong(target[0]),Float.parseFloat(target[1])))
                            return "动作已变化，请重新拖动进度条";
                    }
                    case "pause" -> s.model.toggleMotionPause();
                    case "speed" -> s.model.setMotionSpeed(Float.parseFloat(name));
                    case "restart" -> s.model.restartMotion();
                    case "stop" -> s.model.stopMotion();
                    case "physics" -> {if(s.model.motionActive())s.model.setPhysicsEnabled(!s.model.physicsEnabled());}
                    case "reset" -> {if(s.model.motionActive())s.model.resetPhysics();}
                    default -> {return "未知的动作控制";}
                }
                return "";
            }catch(Exception e){Main.error(e);return e.getMessage()==null?"动作读取失败":e.getMessage();}
        }
    }
    public static synchronized KahluaTable data() throws Exception {
        Path root=Path.of(ZomboidFileSystem.instance.getModDir(Main.MOD_ID));
        Path relative=Path.of("media/ui/AimohPMXReadyRoom/catalog.json");
        Path path=Files.isRegularFile(root.resolve(relative))?root.resolve(relative):root.resolve("42").resolve(relative);
        // UI tables belong to this Lua/window lifecycle, not to a previous game session.
        JSONObject catalog=new JSONObject(Files.readString(path));
        JSONObject models=catalog.getJSONObject("models"),labels=catalog.getJSONObject("labels");
        for(var pack:Main.enabledCharacterPacks()){
            Path file=pack.media().resolve("ui/AimohPMXReadyRoom/packs/"+pack.id()+".json");
            try {
                JSONObject extra=new JSONObject(Files.readString(file)),packModels=extra.getJSONObject("models");
                for(String id:packModels.keySet()){
                    JSONObject card=models.optJSONObject(id);
                    if(card!=null&&pack.id().equals(card.optString("pack")))
                        models.put(id,packModels.getJSONObject(id).put("pack",pack.id()));
                }
                JSONObject packLabels=extra.getJSONObject("labels");
                for(String key:packLabels.keySet())if(!labels.has(key))labels.put(key,packLabels.getJSONObject(key));
            }catch(Exception e){System.err.println("[AimohPMXPreview] Character pack UI "+pack.id()+": "+e);}
        }
        return (KahluaTable)lua(catalog);
    }
    private static Object lua(Object value){
        if(value instanceof JSONObject object){KahluaTable t=J2SEPlatform.getInstance().newTable();for(String key:object.keySet())t.rawset(key,lua(object.get(key)));return t;}
        if(value instanceof JSONArray array){KahluaTable t=J2SEPlatform.getInstance().newTable();for(int i=0;i<array.length();i++)t.rawset((double)i+1,lua(array.get(i)));return t;}
        if(value instanceof Number n)return n.doubleValue();return value==JSONObject.NULL?null:value;
    }
    public static synchronized boolean draw(IsoGameCharacter actor,int x,int y,int width,int height,float yaw,float zoom) {
        return drawCamera(actor,x,y,width,height,yaw,zoom,0,0);
    }
    public static synchronized boolean drawCamera(IsoGameCharacter actor,int x,int y,int width,int height,float yaw,float zoom,float pan,float focus) {
        return drawSession(Main.session(actor),x,y,width,height,yaw,zoom,pan,focus,false);
    }
    public static synchronized boolean drawBrowse(IsoGameCharacter actor,String id,int x,int y,int width,int height,float yaw,float zoom) {
        return drawBrowseCamera(actor,id,x,y,width,height,yaw,zoom,0,0,false);
    }
    public static synchronized boolean drawBrowseCamera(IsoGameCharacter actor,String id,int x,int y,int width,int height,float yaw,float zoom,float pan,float focus,boolean locked) {
        return drawBrowse(actor,id,x,y,width,height,yaw,zoom,pan,focus,locked,false);
    }

    public static synchronized boolean drawOverviewCamera(IsoGameCharacter actor,String id,int x,int y,int width,int height,float yaw,float zoom,float pan,float focus,boolean locked) {
        return drawBrowse(actor,id,x,y,width,height,yaw,zoom,pan,focus,locked,true);
    }

    private static boolean drawBrowse(IsoGameCharacter actor,String id,int x,int y,int width,int height,float yaw,float zoom,float pan,float focus,boolean locked,boolean overview) {
        ModelSession equipped=Main.session(actor);
        if(equipped!=null&&!equipped.entry.id().equals(id))equipped=null;
        if(equipped!=null&&!overview)return drawSession(equipped,x,y,width,height,yaw,zoom,pan,focus,locked);

        var worldPlayer=actor.getAnimationPlayer();
        if(worldPlayer==null||!worldPlayer.isReady())return false;
        BrowsePreview preview=browsePreviews.get(actor);
        if(preview==null||!preview.id.equals(id)||preview.appearanceSource!=null&&equipped==null) {
            release(actor);
            try {
                preview=new BrowsePreview(id,Main.createRoomPreview(id));
            }catch(Exception | LinkageError failure){
                System.err.println("[AimohPMXReadyRoom] Browse preview unavailable: "+failure);
                preview=new BrowsePreview(id,null);
            }
            browsePreviews.put(actor,preview);
        }
        if(preview.session==null||preview.session.failed)return false;
        try {
            preview.update(equipped,worldPlayer);
        }catch(Exception | LinkageError failure){
            preview.session.fail(failure);
            return false;
        }
        return drawSession(preview.session,x,y,width,height,yaw,zoom,pan,focus,locked);
    }
    private static boolean drawSession(ModelSession session,int x,int y,int width,int height,float yaw,float zoom,float pan,float focus,boolean locked) {
        if(session==null||!session.ready||session.retired||session.failed||width<16||height<16)return false;
        try {
            ModelSession.Snapshot snapshot=session.snapshot();if(snapshot==null)return false;
            Mesh mesh=session.plainMesh(snapshot.frame());
            SpriteRenderer.instance.drawGeneric(new Draw(mesh,snapshot.frame(),snapshot.materials(),x,y,width,height,Core.height,yaw,zoom,pan,focus,locked));
            return true;
        }catch(Exception e){System.err.println("[AimohPMXReadyRoom] Preview unavailable: "+e);return false;}
    }
    public static synchronized void release(IsoGameCharacter actor) {
        BrowsePreview preview=browsePreviews.remove(actor);
        if(preview!=null&&preview.session!=null)preview.session.retire();
    }
    private record PreviewProgram(int id,int vp,int turn,int image,int locked) {
        // LWJGL keeps these capabilities with their GL context. The context owns the program.
        private static final WeakHashMap<GLCapabilities,PreviewProgram> programs=new WeakHashMap<>();

        static synchronized PreviewProgram current() {
            return programs.computeIfAbsent(GL.getCapabilities(),context->link());
        }

        private static int shader(int kind,String source){
            int id=GL20.glCreateShader(kind);GL20.glShaderSource(id,source);GL20.glCompileShader(id);
            if(GL20.glGetShaderi(id,GL20.GL_COMPILE_STATUS)==0){String error=GL20.glGetShaderInfoLog(id);GL20.glDeleteShader(id);throw new IllegalStateException(error);}return id;
        }

        private static PreviewProgram link() {
            int vertex=shader(GL20.GL_VERTEX_SHADER,"""
                #version 330
                layout(location=0) in vec3 position;layout(location=1) in vec3 normal;layout(location=2) in vec2 texcoord;
                uniform mat4 vp;uniform mat4 turn;out vec3 n;out vec2 uv;
                void main(){gl_Position=vp*turn*vec4(position,1);n=mat3(turn)*normal;uv=texcoord;}
                """);
            int fragment=0,program=0;
            try {
                fragment=shader(GL20.GL_FRAGMENT_SHADER,"""
                    #version 330
                    in vec3 n;in vec2 uv;uniform sampler2D image;uniform bool locked;out vec4 color;
                    void main(){vec4 c=texture(image,uv);if(c.a<.05)discard;
                    float light=.76+.24*max(dot(normalize(n),normalize(vec3(.35,.8,-.6))),0.);
                    if(locked){
                        float l=dot(c.rgb,vec3(.2126,.7152,.0722));
                        color=vec4(mix(vec3(.08,.16,.18),vec3(.57,.75,.76),l)*light,c.a*.47);
                    }else color=vec4(c.rgb*light,c.a);}
                    """);
                program=GL20.glCreateProgram();GL20.glAttachShader(program,vertex);GL20.glAttachShader(program,fragment);GL20.glLinkProgram(program);
                if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
                return new PreviewProgram(program,GL20.glGetUniformLocation(program,"vp"),GL20.glGetUniformLocation(program,"turn"),
                    GL20.glGetUniformLocation(program,"image"),GL20.glGetUniformLocation(program,"locked"));
            } catch(RuntimeException|Error failure) {
                if(program!=0)GL20.glDeleteProgram(program);
                throw failure;
            } finally {GL20.glDeleteShader(vertex);if(fragment!=0)GL20.glDeleteShader(fragment);}
        }
    }
    private static ByteBuffer buffer(int bytes){return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());}
    static final class Mesh implements AutoCloseable {
        final ModelSession session;
        final float[] uv;
        final int[] indices,materials,firsts,counts;
        final ByteBuffer upload;
        final float centerX,centerY,centerZ,span,faceY;
        int vao,vbo,ibo,uvBuffer;
        boolean closed,failed,gpuLayout;
        private PmxDrawMesh.FrameData uploaded;
        Mesh(ModelSession s,PmxDrawMesh.FrameData frame) {
            session=s;int count=frame.positions().length/3;
            ByteBuffer bytes=buffer(count*8);s.api.CopyDataToByteBuffer(bytes,s.api.GetUVs(s.model.handle()),bytes.capacity());
            uv=new float[count*2];bytes.asFloatBuffer().get(uv);upload=buffer(count*32);
            int size=Math.toIntExact(s.api.GetIndexElementSize(s.model.handle()));
            if(size!=1&&size!=2&&size!=4)throw new IllegalArgumentException("PMX index size");
            indices=new int[Math.toIntExact(s.api.GetIndexCount(s.model.handle()))];bytes=buffer(indices.length*size);
            s.api.CopyDataToByteBuffer(bytes,s.api.GetIndices(s.model.handle()),bytes.capacity());
            for(int i=0;i<indices.length;i++)indices[i]=size==1?Byte.toUnsignedInt(bytes.get(i)):size==2?Short.toUnsignedInt(bytes.getShort(i*2)):bytes.getInt(i*4);
            int parts=Math.toIntExact(s.api.GetSubMeshCount(s.model.handle()));materials=new int[parts];firsts=new int[parts];counts=new int[parts];
            for(int i=0;i<parts;i++){materials[i]=s.api.GetSubMeshMaterialID(s.model.handle(),i);firsts[i]=s.api.GetSubMeshBeginIndex(s.model.handle(),i);counts[i]=s.api.GetSubMeshVertexCount(s.model.handle(),i);}
            float[] p=frame.positions();float minX=Float.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=-minX,maxY=-minX,maxZ=-minX;
            for(int i=0;i<p.length;i+=3){minX=Math.min(minX,p[i]);maxX=Math.max(maxX,p[i]);minY=Math.min(minY,p[i+1]);maxY=Math.max(maxY,p[i+1]);minZ=Math.min(minZ,p[i+2]);maxZ=Math.max(maxZ,p[i+2]);}
            centerX=(minX+maxX)/2;centerY=(minY+maxY)/2;centerZ=(minZ+maxZ)/2;
            span=Math.max(1,maxY-minY)*1.20f;
            float head=maxY-(maxY-minY)*.115f;
            if(s.targetHead>=0) {
                JSONArray bones=new JSONArray(s.api.GetBoneMetadata(s.model.handle()));
                JSONObject bone=bones.optJSONObject(s.targetHead);
                JSONArray pos=bone==null?null:bone.optJSONArray("initial_position");
                if(pos!=null&&pos.length()>1) {
                    float y=(float)pos.optDouble(1);
                    if(y>centerY&&y<maxY)head=Math.min(maxY,y+.027f*span);
                }
            }
            faceY=head;
        }
        private void initialize(){
            vao=GL30.glGenVertexArrays();GL30.glBindVertexArray(vao);vbo=GL15.glGenBuffers();ibo=GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);GL15.glBufferData(GL15.GL_ARRAY_BUFFER,upload.capacity(),GL15.GL_STREAM_DRAW);
            for(int i=0;i<3;i++)GL20.glEnableVertexAttribArray(i);
            bindCpuLayout();
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,ibo);GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER,indices,GL15.GL_STATIC_DRAW);
        }
        private void bindCpuLayout() {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);
            for(int i=0;i<3;i++)GL20.glVertexAttribPointer(i,i==2?2:3,GL11.GL_FLOAT,false,32,i==0?0L:i==1?12L:24L);
            gpuLayout=false;
        }
        void bindGeometry(PmxDrawMesh.FrameData frame) {
            if(vao==0)initialize();
            GL30.glBindVertexArray(vao);
            if(frame.gpuPose()!=null) {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,frame.gpuPose().vertexBuffer());
                GL20.glVertexAttribPointer(0,3,GL11.GL_FLOAT,false,32,0L);
                GL20.glVertexAttribPointer(1,3,GL11.GL_FLOAT,false,32,16L);
                if(uvBuffer==0){uvBuffer=GL15.glGenBuffers();GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,uvBuffer);
                    GL15.glBufferData(GL15.GL_ARRAY_BUFFER,uv,GL15.GL_STATIC_DRAW);}
                else GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,uvBuffer);
                GL20.glVertexAttribPointer(2,2,GL11.GL_FLOAT,false,8,0L);
                gpuLayout=true;
                return;
            }
            if(gpuLayout)bindCpuLayout();else GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);
            if(uploaded==frame)return;
            upload.clear();
            float[] p=frame.positions(),n=frame.normals();
            for(int i=0;i<p.length/3;i++)upload.putFloat(p[i*3]).putFloat(p[i*3+1]).putFloat(p[i*3+2]).putFloat(n[i*3]).putFloat(n[i*3+1]).putFloat(n[i*3+2]).putFloat(uv[i*2]).putFloat(uv[i*2+1]);
            upload.flip();GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER,0,upload);
            uploaded=frame;
        }
        void paint(PmxDrawMesh.FrameData frame,ModelSession.Materials state,int width,int height,float yaw,float zoom,float pan,float focus,boolean locked)throws Exception {
            bindGeometry(frame);
            float extent=span/Math.clamp(zoom,.62f,4.2f),aspect=(float)width/height;
            float cameraY=centerY+(faceY-centerY)*Math.clamp(focus,0,1)+span*Math.clamp(pan,-.8f,.8f);
            Matrix4f vp=new Matrix4f().ortho(-extent*aspect/2,extent*aspect/2,-extent/2,extent/2,.1f,span*8)
                .lookAt(centerX,cameraY,centerZ+span*3,centerX,cameraY,centerZ,0,1,0);
            Matrix4f turn=new Matrix4f().translate(centerX,0,centerZ).rotateY(yaw).translate(-centerX,0,-centerZ);
            PreviewProgram program=PreviewProgram.current();
            GL20.glUseProgram(program.id());GL20.glUniformMatrix4fv(program.vp(),false,vp.get(new float[16]));
            GL20.glUniformMatrix4fv(program.turn(),false,turn.get(new float[16]));GL20.glUniform1i(program.image(),0);
            GL20.glUniform1i(program.locked(),locked?1:0);
            Texture[] textures=session.preparePlainMaterials(state);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);GL33.glBindSampler(0,0);GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LEQUAL);GL11.glDepthMask(true);
            GL11.glEnable(GL11.GL_BLEND);GL14.glBlendEquation(GL14.GL_FUNC_ADD);GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA);
            for(int i=0;i<materials.length;i++){
                int material=materials[i];if(frame.materials()[material*12+3]<=0)continue;
                GL11.glBindTexture(GL11.GL_TEXTURE_2D,textures[material].getID());
                GL11.glDrawElements(GL11.GL_TRIANGLES,counts[i],GL11.GL_UNSIGNED_INT,(long)firsts[i]*4);
            }
        }
        @Override public void close(){if(closed)return;closed=true;uploaded=null;if(uvBuffer!=0)GL15.glDeleteBuffers(uvBuffer);if(vbo!=0)GL15.glDeleteBuffers(vbo);if(ibo!=0)GL15.glDeleteBuffers(ibo);if(vao!=0)GL30.glDeleteVertexArrays(vao);}
    }
    static final class Draw extends TextureDraw.GenericDrawer {
        final Mesh mesh;final PmxDrawMesh.FrameData frame;final ModelSession.Materials materials;
        final int x,y,width,height,screenHeight;final float yaw,zoom,pan,focus;final boolean locked;
        Draw(Mesh mesh,PmxDrawMesh.FrameData frame,ModelSession.Materials materials,int x,int y,int w,int h,int sh,float yaw,float zoom){
            this(mesh,frame,materials,x,y,w,h,sh,yaw,zoom,0,0,false);
        }
        Draw(Mesh mesh,PmxDrawMesh.FrameData frame,ModelSession.Materials materials,int x,int y,int w,int h,int sh,float yaw,float zoom,float pan,float focus,boolean locked){
            this.mesh=mesh;this.frame=frame;this.materials=materials;this.x=x;this.y=y;width=w;height=h;screenHeight=sh;this.yaw=yaw;this.zoom=zoom;
            this.pan=pan;this.focus=focus;this.locked=locked;
        }
        public void render(){
            paintNow();
        }
        boolean paintNow(){
            if(mesh.closed||mesh.failed||mesh.session.retired)return false;
            State saved=new State();
            try {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);GL11.glScissor(x,screenHeight-y-height,width,height);GL11.glViewport(x,screenHeight-y-height,width,height);
                GL11.glDepthMask(true);GL11.glClearDepth(1);GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                mesh.paint(frame,materials,width,height,yaw,zoom,pan,focus,locked);
                return true;
            }catch(Exception|LinkageError e){
                if(frame.gpuPose()!=null)mesh.session.disableGpu(e);
                else {mesh.failed=true;System.err.println("[AimohPMXReadyRoom] Preview draw failed: "+e);}
                return false;
            }
            finally {
                saved.restore();
                SpriteRenderer.ringBuffer.restoreVbos=true;SpriteRenderer.ringBuffer.restoreBoundTextures=true;
            }
        }
    }
    // Core-profile-safe state scope; legacy glPushAttrib is unavailable on some hosts.
    static final class State {
        final int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),vao=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),vbo=GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        final int unit=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE),depthFunc=GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        final int srcRGB=GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),dstRGB=GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),srcAlpha=GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),dstAlpha=GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        final int equationRGB=GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB),equationAlpha=GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        final boolean blend=GL11.glIsEnabled(GL11.GL_BLEND),depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE),scissor=GL11.glIsEnabled(GL11.GL_SCISSOR_TEST),depthMask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        final double clearDepth=GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE);
        final int[] viewport=new int[4],box=new int[4];final int texture,sampler;
        State(){GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX,box);GL13.glActiveTexture(GL13.GL_TEXTURE0);texture=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);sampler=GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,0);}
        static void enabled(int flag,boolean value){if(value)GL11.glEnable(flag);else GL11.glDisable(flag);}
        void restore(){
            GL11.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);GL11.glScissor(box[0],box[1],box[2],box[3]);
            enabled(GL11.GL_BLEND,blend);enabled(GL11.GL_DEPTH_TEST,depth);enabled(GL11.GL_CULL_FACE,cull);enabled(GL11.GL_SCISSOR_TEST,scissor);
            GL14.glBlendFuncSeparate(srcRGB,dstRGB,srcAlpha,dstAlpha);GL20.glBlendEquationSeparate(equationRGB,equationAlpha);GL11.glDepthMask(depthMask);GL11.glDepthFunc(depthFunc);GL11.glClearDepth(clearDepth);
            GL20.glUseProgram(program);GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL33.glBindSampler(0,sampler);GL13.glActiveTexture(unit);
        }
    }
}
