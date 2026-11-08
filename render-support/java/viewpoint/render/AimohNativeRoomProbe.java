package viewpoint.render;
import java.util.*;
import java.util.regex.*;
import org.joml.*;
import org.lwjgl.opengl.*;
import viewpoint.platform.*;
import zombie.core.textures.Texture;

/** Native scene geometry in six directions. Original character shaders are not part of this capture. */
public final class AimohNativeRoomProbe implements AutoCloseable {
    public static final int SIZE=64;
    public int albedo,normal,engine,depth,radiance;
    private int framebuffer,lightFramebuffer,neutralOcclusion,emptyReflection;
    private final int[][] views=new int[6][4];
    private GlProgram geometry,lighting;
    private Inputs geometryInputs,lightInputs;
    private final Matrix4f[] matrices=new Matrix4f[6];
    private byte[] savedFlags=new byte[0];
    private int[] savedPlan=new int[0];
    private int captures,meshes;
    private static final float[][] FORWARD={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}},UP={{0,-1,0},{0,-1,0},{0,0,1},{0,0,-1},{0,-1,0},{0,-1,0}};
    private record Varying(String qualifier,String type,String name){}

    public AimohNativeRoomProbe()throws Exception{
        try{
            if(GL11.glGetInteger(GL40.GL_MAX_GEOMETRY_SHADER_INVOCATIONS)<6)throw new IllegalStateException("Native six-direction geometry capture is unavailable");
            String vertex=AimohNativePackSelection.expanded("mesh.vert").replaceFirst("#version\\s+\\d+","#version 430");
            String fragment=AimohNativePackSelection.expanded("gbuffer.frag").replaceFirst("#version\\s+\\d+","#version 430");
            List<Varying> varyings=new ArrayList<>();Matcher declarations=Pattern.compile("(?m)^(flat\\s+)?out\\s+(float|int|vec[234])\\s+(v\\w+)\\s*;").matcher(vertex);
            while(declarations.find())varyings.add(new Varying(declarations.group(1)==null?"":declarations.group(1),declarations.group(2),declarations.group(3)));
            if(varyings.size()!=12||varyings.stream().noneMatch(v->v.name.equals("vPos"))||varyings.stream().noneMatch(v->v.name.equals("vDistance")))throw new IllegalArgumentException("Native mesh varying contract changed");
            String hidden="gl_Position = vec4(2.0, 2.0, 2.0, 1.0);",entry="MeshVertex v = meshVertex(uEye);";
            if(!vertex.contains(hidden)||!vertex.contains(entry))throw new IllegalArgumentException("Native mesh visibility contract changed");
            vertex=vertex.replace("void main()","flat out int gf2ProbeHidden;\nvoid main()").replace(entry,"gf2ProbeHidden=0;\n"+entry).replace(hidden,"gf2ProbeHidden=1;"+hidden);
            StringBuilder gs=new StringBuilder("#version 430\nlayout(triangles,invocations=6)in;layout(triangle_strip,max_vertices=3)out;uniform mat4 gf2ProbeViewProjection[6];flat in int gf2ProbeHidden[];\n");
            for(Varying v:varyings){
                vertex=vertex.replaceAll("\\b"+v.name+"\\b","gf2ProbeIn_"+v.name);
                gs.append(v.qualifier).append("in ").append(v.type).append(" gf2ProbeIn_").append(v.name).append("[];\n").append(v.qualifier).append("out ").append(v.type).append(' ').append(v.name).append(";\n");
            }
            gs.append("void main(){if(gf2ProbeHidden[0]!=0||gf2ProbeHidden[1]!=0||gf2ProbeHidden[2]!=0)return;vec4 clip[3];for(int i=0;i<3;i++)clip[i]=gf2ProbeViewProjection[gl_InvocationID]*vec4(gf2ProbeIn_vPos[i],1);\n");
            // Reject only triangles the fixed-function clipper would discard on this cube face.
            gs.append("for(int axis=0;axis<3;axis++){if(clip[0][axis]<-clip[0].w&&clip[1][axis]<-clip[1].w&&clip[2][axis]<-clip[2].w)return;if(clip[0][axis]>clip[0].w&&clip[1][axis]>clip[1].w&&clip[2][axis]>clip[2].w)return;}for(int i=0;i<3;i++){gl_Position=clip[i];gl_Layer=gl_InvocationID;\n");
            for(Varying v:varyings)gs.append(v.name).append('=').append(v.name.equals("vDistance")?"gl_Position.w":"gf2ProbeIn_"+v.name+"[i]").append(";\n");
            gs.append("EmitVertex();}EndPrimitive();}");
            geometry=GlProgram.fromSources("GF2 Native room geometry",new GlProgram.Sources(vertex,null,null,gs.toString(),fragment),Map.of(),Map.of(),List.of());
            if(geometry.id()==0)throw new IllegalStateException(GlProgram.lastError());
            Object surfaces=AimohNativeGf2Environment.world("surfaces");GlProgram nativeGeometry=(GlProgram)AimohNativeGf2Environment.field(surfaces,"gbuffer");geometryInputs=new Inputs(nativeGeometry,geometry);
            lighting=GlProgram.fromSources("GF2 Native room lighting",new GlProgram.Sources(AimohNativePackSelection.expanded("screen.vert"),null,null,null,AimohNativePackSelection.expanded("light.frag")),Map.of(),Map.of(),List.of());
            if(lighting.id()==0)throw new IllegalStateException(GlProgram.lastError());lightInputs=new Inputs((GlProgram)AimohNativeGf2Environment.field(surfaces,"light"),lighting);
            albedo=cube(GL11.GL_RGBA8);normal=cube(GL30.GL_RGB10_A2);engine=cube(GL11.GL_RGBA8);depth=cube(GL30.GL_DEPTH24_STENCIL8);
            radiance=cube(GL30.GL_RGBA16F);lightFramebuffer=GL45.glCreateFramebuffers();
            int[] textures={albedo,normal,engine,depth},formats={GL11.GL_RGBA8,GL30.GL_RGB10_A2,GL11.GL_RGBA8,GL30.GL_DEPTH24_STENCIL8};
            for(int face=0;face<6;face++)for(int i=0;i<4;i++){int view=views[face][i]=GL11.glGenTextures();GL43.glTextureView(view,GL11.GL_TEXTURE_2D,textures[i],formats[i],0,1,face,1);GL45.glTextureParameteri(view,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL45.glTextureParameteri(view,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);}
            try(AimohGf2UnpackScope unpack=new AimohGf2UnpackScope()){
                neutralOcclusion=GL45.glCreateTextures(GL11.GL_TEXTURE_2D);GL45.glTextureStorage2D(neutralOcclusion,1,GL11.GL_RGBA8,SIZE,SIZE);GL44.glClearTexImage(neutralOcclusion,0,GL11.GL_RGBA,GL11.GL_FLOAT,new float[]{.5f,.5f,.5f,1});
                emptyReflection=GL45.glCreateTextures(GL11.GL_TEXTURE_2D);GL45.glTextureStorage2D(emptyReflection,1,GL30.GL_RGBA16F,1,1);GL44.glClearTexImage(emptyReflection,0,GL11.GL_RGBA,GL11.GL_FLOAT,new float[]{0,0,0,-1});
            }
            for(int texture:new int[]{neutralOcclusion,emptyReflection}){GL45.glTextureParameteri(texture,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL45.glTextureParameteri(texture,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);}
            framebuffer=GL45.glCreateFramebuffers();for(int i=0;i<3;i++)GL45.glNamedFramebufferTexture(framebuffer,GL30.GL_COLOR_ATTACHMENT0+i,new int[]{albedo,normal,engine}[i],0);GL45.glNamedFramebufferTexture(framebuffer,GL30.GL_DEPTH_STENCIL_ATTACHMENT,depth,0);
            GL45.glNamedFramebufferDrawBuffers(framebuffer,new int[]{GL30.GL_COLOR_ATTACHMENT0,GL30.GL_COLOR_ATTACHMENT1,GL30.GL_COLOR_ATTACHMENT2});
            if(GL45.glCheckNamedFramebufferStatus(framebuffer,GL30.GL_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Native layered room GBuffer is incomplete");
        }catch(Exception e){close();throw e;}
    }
    private static int cube(int format){
        int texture=GL45.glCreateTextures(GL13.GL_TEXTURE_CUBE_MAP);GL45.glTextureStorage2D(texture,1,format,SIZE,SIZE);GL45.glTextureParameteri(texture,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL45.glTextureParameteri(texture,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
        for(int axis:new int[]{GL11.GL_TEXTURE_WRAP_S,GL11.GL_TEXTURE_WRAP_T,GL12.GL_TEXTURE_WRAP_R})GL45.glTextureParameteri(texture,axis,GL12.GL_CLAMP_TO_EDGE);return texture;
    }
    public void capture(FrameContext frame,float[] position)throws Exception{
        if(position.length!=3)throw new IllegalArgumentException("Probe position shape differs");for(float v:position)if(!Float.isFinite(v))throw new IllegalArgumentException("Non-finite probe position");
        SceneData scene=frame.scene;int count=scene.meshCount;if(savedFlags.length<count){savedFlags=new byte[count];savedPlan=new int[count];}System.arraycopy(scene.meshFlags,0,savedFlags,0,count);System.arraycopy(scene.meshPlan,0,savedPlan,0,count);
        int cachedTexture=Texture.lastTextureID;ExtraTextures extra=new ExtraTextures();
        try(AimohNativeSourceScope scope=new AimohNativeSourceScope(true)){
            geometryInputs.copy();geometry.use();geometry.set("uEye",position[0],position[1],position[2]);geometry.set("uViewer",position[0],position[1],position[2]);
            Matrix4f projection=new Matrix4f().perspective((float)(java.lang.Math.PI*.5),1,.05f,400);
            for(int face=0;face<6;face++){
                float[] f=FORWARD[face],up=UP[face];matrices[face]=new Matrix4f(projection).lookAt(position[0],position[1],position[2],position[0]+f[0],position[1]+f[1],position[2]+f[2],up[0],up[1],up[2]);geometry.set("gf2ProbeViewProjection["+face+"]",matrices[face]);
            }
            geometry.set("uViewProjection",matrices[0]);geometry.setInt("uModels",0);geometry.setInt("uPlants",0);geometry.setInt("uCards",0);geometry.setInt("uNoFoliage",0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,framebuffer);GL11.glViewport(0,0,SIZE,SIZE);GL11.glDisable(GL11.GL_SCISSOR_TEST);GL11.glDisable(GL11.GL_STENCIL_TEST);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL32.GL_DEPTH_CLAMP);GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LESS);GL11.glDepthMask(true);GL11.glDepthRange(0,1);
            for(int i=0;i<3;i++){GL30.glDisablei(GL11.GL_BLEND,i);GL30.glColorMaski(i,true,true,true,true);GL30.glClearBufferfv(GL11.GL_COLOR,i,new float[4]);}GL30.glClearBufferfi(GL30.GL_DEPTH_STENCIL,0,1,0);
            // A reflection capture needs available geometry outside the main camera's triangle plan.
            for(int i=0;i<count;i++){scene.meshFlags[i]|=1;scene.meshPlan[i]=-1;}
            Meshes.drawPlanned(scene,geometry,(byte)1,true);GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT|GL42.GL_FRAMEBUFFER_BARRIER_BIT);captures++;meshes=count;
        }finally{System.arraycopy(savedFlags,0,scene.meshFlags,0,count);System.arraycopy(savedPlan,0,scene.meshPlan,0,count);extra.close();Texture.lastTextureID=cachedTexture;}
    }
    public int captures(){return captures;}
    public int availableMeshes(){return meshes;}
    public void illuminate(FrameContext frame,float[] position)throws Exception{
        if(captures==0)throw new IllegalStateException("Room lighting requires captured geometry");
        try(AimohNativeSourceScope scope=new AimohNativeSourceScope()){
            lightInputs.copy();lighting.use();lighting.set("uEye",position[0],position[1],position[2]);lighting.set("uCamera",.05f,400f,1f,1f);lighting.setInt("uDebugView",0);lighting.setInt("uBlobCount",0);lighting.setInt("uContactShadows",0);
            ShadowPass shadows=(ShadowPass)AimohNativeGf2Environment.world("shadows");ShadowPass.samplers(lighting);LampShadows.sampler(lighting);Spots.sampler(lighting);shadows.bind(true);
            lighting.sampler("uAlbedo",9);lighting.sampler("uNormal",10);lighting.sampler("uEngineLight",11);lighting.sampler("uDepth",12);
            int location=GL20.glGetUniformLocation(lighting.id(),"uOcclusion");if(location>=0){int unit=GL20.glGetUniformi(lighting.id(),location);GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);GL11.glBindTexture(GL11.GL_TEXTURE_2D,neutralOcclusion);GL33.glBindSampler(unit,0);}
            // A cubemap has no main-view SSAO, contact-shadow buffer or recursive screen reflection.
            lighting.sampler("uReflection",119);lighting.sampler("uReflectionDepth",120);GL13.glActiveTexture(GL13.GL_TEXTURE0+119);GL11.glBindTexture(GL11.GL_TEXTURE_2D,emptyReflection);GL33.glBindSampler(119,0);GL13.glActiveTexture(GL13.GL_TEXTURE0+120);GL11.glBindTexture(GL11.GL_TEXTURE_2D,views[0][3]);GL33.glBindSampler(120,0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,lightFramebuffer);GL11.glViewport(0,0,SIZE,SIZE);for(int cap:new int[]{GL11.GL_SCISSOR_TEST,GL11.GL_DEPTH_TEST,GL11.GL_STENCIL_TEST,GL11.GL_CULL_FACE})GL11.glDisable(cap);GL11.glDepthMask(false);GL30.glDisablei(GL11.GL_BLEND,0);GL30.glColorMaski(0,true,true,true,true);
            for(int face=0;face<6;face++){
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X+face,radiance,0);GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[4]);
                lighting.set("uInvViewProjection",new Matrix4f(matrices[face]).invert());
                for(int i=0;i<4;i++){GL13.glActiveTexture(GL13.GL_TEXTURE0+9+i);GL11.glBindTexture(GL11.GL_TEXTURE_2D,views[face][i]);GL33.glBindSampler(9+i,0);}Gl.screenTriangle();
            }
            GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT|GL42.GL_FRAMEBUFFER_BARRIER_BIT);
        }
    }
    public long bytes(){return (long)SIZE*SIZE*(6*24+4)+8;}
    @Override public void close(){AimohNativeSourceScope.forget(geometry,lighting);if(geometry!=null)geometry.delete();if(lighting!=null)lighting.delete();for(int[] face:views){for(int id:face)if(id!=0)GL11.glDeleteTextures(id);Arrays.fill(face,0);}for(int id:new int[]{albedo,normal,engine,depth,radiance,neutralOcclusion,emptyReflection})if(id!=0)GL11.glDeleteTextures(id);for(int id:new int[]{framebuffer,lightFramebuffer})if(id!=0)GL30.glDeleteFramebuffers(id);geometry=lighting=null;albedo=normal=engine=depth=radiance=framebuffer=lightFramebuffer=neutralOcclusion=emptyReflection=0;}

    /** Mesh floor arrays, plant records and corpse-card records are outside the character draw scope. */
    private static final class ExtraTextures implements AutoCloseable{
        final int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);final int[] units={41,48,90,136};final int[][] values=new int[4][5];
        ExtraTextures(){for(int i=0;i<units.length;i++){GL13.glActiveTexture(GL13.GL_TEXTURE0+units[i]);values[i]=new int[]{GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY),GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER),GL11.glGetInteger(GL13.GL_TEXTURE_BINDING_CUBE_MAP),GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,units[i])};}GL13.glActiveTexture(active);}
        public void close(){for(int i=0;i<units.length;i++){GL13.glActiveTexture(GL13.GL_TEXTURE0+units[i]);GL11.glBindTexture(GL11.GL_TEXTURE_2D,values[i][0]);GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY,values[i][1]);GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,values[i][2]);GL11.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP,values[i][3]);GL33.glBindSampler(units[i],values[i][4]);}GL13.glActiveTexture(active);}
    }
    /** Copy the actual Native geometry uniforms; shader layouts are cached, not queried every face. */
    private static final class Inputs{
        private final GlProgram source,target;private final List<Uniform> uniforms=new ArrayList<>();
        Inputs(GlProgram source,GlProgram target){
            this.source=source;this.target=target;int count=GL20.glGetProgrami(target.id(),GL20.GL_ACTIVE_UNIFORMS);
            for(int i=0;i<count;i++){
                var size=org.lwjgl.BufferUtils.createIntBuffer(1);var type=org.lwjgl.BufferUtils.createIntBuffer(1);String name=GL20.glGetActiveUniform(target.id(),i,512,size,type);int n=size.get(0);
                for(int a=0;a<n;a++){String element=n==1?name:name.replace("[0]","["+a+"]");int from=GL20.glGetUniformLocation(source.id(),element),to=GL20.glGetUniformLocation(target.id(),element);if(from>=0&&to>=0)uniforms.add(new Uniform(from,to,type.get(0)));}
            }
            for(int i=0;i<GL20.glGetProgrami(target.id(),GL31.GL_ACTIVE_UNIFORM_BLOCKS);i++){String name=GL31.glGetActiveUniformBlockName(target.id(),i,512);int block=GL31.glGetUniformBlockIndex(source.id(),name);if(block!=GL31.GL_INVALID_INDEX)GL31.glUniformBlockBinding(target.id(),i,GL31.glGetActiveUniformBlocki(source.id(),block,GL31.GL_UNIFORM_BLOCK_BINDING));}
        }
        void copy(){for(Uniform u:uniforms)u.copy(source.id(),target.id());}
    }
    private static final class Uniform{
        final int from,to,type,components;final float[] floats;final int[] ints;
        Uniform(int from,int to,int type){this.from=from;this.to=to;this.type=type;components=switch(type){case GL20.GL_FLOAT_VEC2,GL20.GL_INT_VEC2,GL20.GL_BOOL_VEC2,GL30.GL_UNSIGNED_INT_VEC2->2;case GL20.GL_FLOAT_VEC3,GL20.GL_INT_VEC3,GL20.GL_BOOL_VEC3,GL30.GL_UNSIGNED_INT_VEC3->3;case GL20.GL_FLOAT_VEC4,GL20.GL_INT_VEC4,GL20.GL_BOOL_VEC4,GL30.GL_UNSIGNED_INT_VEC4->4;case GL20.GL_FLOAT_MAT2->4;case GL20.GL_FLOAT_MAT3->9;case GL20.GL_FLOAT_MAT4->16;default->1;};floats=new float[components];ints=new int[components];}
        void copy(int source,int target){
            if(type==GL20.GL_FLOAT_MAT4){GL20.glGetUniformfv(source,from,floats);GL41.glProgramUniformMatrix4fv(target,to,false,floats);return;}
            if(type==GL20.GL_FLOAT_MAT3){GL20.glGetUniformfv(source,from,floats);GL41.glProgramUniformMatrix3fv(target,to,false,floats);return;}
            if(type==GL20.GL_FLOAT_MAT2){GL20.glGetUniformfv(source,from,floats);GL41.glProgramUniformMatrix2fv(target,to,false,floats);return;}
            if(type==GL11.GL_FLOAT||type==GL20.GL_FLOAT_VEC2||type==GL20.GL_FLOAT_VEC3||type==GL20.GL_FLOAT_VEC4){GL20.glGetUniformfv(source,from,floats);switch(components){case 1->GL41.glProgramUniform1fv(target,to,floats);case 2->GL41.glProgramUniform2fv(target,to,floats);case 3->GL41.glProgramUniform3fv(target,to,floats);case 4->GL41.glProgramUniform4fv(target,to,floats);}return;}
            if(type==GL11.GL_UNSIGNED_INT||type==GL30.GL_UNSIGNED_INT_VEC2||type==GL30.GL_UNSIGNED_INT_VEC3||type==GL30.GL_UNSIGNED_INT_VEC4){GL30.glGetUniformuiv(source,from,ints);switch(components){case 1->GL41.glProgramUniform1uiv(target,to,ints);case 2->GL41.glProgramUniform2uiv(target,to,ints);case 3->GL41.glProgramUniform3uiv(target,to,ints);case 4->GL41.glProgramUniform4uiv(target,to,ints);}return;}
            GL20.glGetUniformiv(source,from,ints);switch(components){case 1->GL41.glProgramUniform1iv(target,to,ints);case 2->GL41.glProgramUniform2iv(target,to,ints);case 3->GL41.glProgramUniform3iv(target,to,ints);case 4->GL41.glProgramUniform4iv(target,to,ints);}
        }
    }
}
