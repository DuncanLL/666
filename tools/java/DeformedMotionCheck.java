import aimoh.pmxpreview.MeshVertexPatch;
import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.util.Arrays;
import org.json.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

/** Executes installed host shaders, including disabled-array values permitted by OpenGL. */
public final class DeformedMotionCheck {
    public static Instrumentation instrumentation;
    public static void premain(String ignored,Instrumentation value){instrumentation=value;}
    static void installReader()throws Exception{
        var loader=Class.forName("me.zed_0xff.zombie_buddy.Loader");var agent=loader.getDeclaredField("g_instrumentation");agent.setAccessible(true);agent.set(null,instrumentation);
        me.zed_0xff.zombie_buddy.PatchEngine.applyPatches("aimoh.pmxpreview",DeformedMotionCheck.class.getClassLoader());
    }
    static String source(String name)throws Exception{
        Class<?> packs=Class.forName("viewpoint.platform.ShaderPacks"),active=Class.forName("viewpoint.platform.ShaderPacks$Active");
        var library=packs.getDeclaredMethod("library",int.class);library.setAccessible(true);
        var load=Class.forName("viewpoint.platform.Shaders").getDeclaredMethod("load",String.class,active);load.setAccessible(true);
        Object expanded=load.invoke(null,name,library.invoke(null,0));var text=expanded.getClass().getDeclaredField("text");text.setAccessible(true);return(String)text.get(expanded);
    }
    static int program(String source,boolean iris){
        source=source.replaceAll("(?m)^#version[^\\n]*","");
        source="#version 430 compatibility\n"+source;
        if(iris)source+="\nout vec4 checkCurrent;out vec4 checkPrevious;void main(){fetchModel();checkCurrent=vec4(toMinecraft(vPos),1);checkPrevious=vec4(toMinecraft(vPos)-vp_velocity,1);gl_Position=vec4(vPos,1);}";
        int vs=GL20.glCreateShader(GL20.GL_VERTEX_SHADER);GL20.glShaderSource(vs,source);GL20.glCompileShader(vs);
        if(GL20.glGetShaderi(vs,GL20.GL_COMPILE_STATUS)==0)throw new AssertionError(GL20.glGetShaderInfoLog(vs));
        int p=GL20.glCreateProgram();GL20.glAttachShader(p,vs);
        GL30.glTransformFeedbackVaryings(p,iris?new CharSequence[]{"checkCurrent","checkPrevious"}:new CharSequence[]{"gl_Position","vPrevClip"},GL30.GL_INTERLEAVED_ATTRIBS);
        GL20.glLinkProgram(p);GL20.glDeleteShader(vs);
        if(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)==0)throw new AssertionError(GL20.glGetProgramInfoLog(p));
        for(String name:new String[]{"ModelPalettes","ModelDraws"}){
            int block=GL43.glGetProgramResourceIndex(p,GL43.GL_SHADER_STORAGE_BLOCK,name);
            if(block!=GL43.GL_INVALID_INDEX)GL43.glShaderStorageBlockBinding(p,block,name.equals("ModelPalettes")?8:9);
        }
        GL20.glUseProgram(p);float[] identity={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
        for(String name:new String[]{"uViewProjection","uPlainViewProjection","uPrevViewProjection"}){int loc=GL20.glGetUniformLocation(p,name);if(loc>=0)GL20.glUniformMatrix4fv(loc,false,identity);}
        int loc=GL20.glGetUniformLocation(p,"uMotionPass");if(loc>=0)GL20.glUniform1i(loc,1);
        return p;
    }
    static float[] run(int program,int flags,float[] position,float[] weights,boolean skinned,int recordBuffer,int feedback){
        float[] record=new float[68];for(int base:new int[]{0,36})for(int i=0;i<4;i++)record[base+i*5]=1;
        record[52]=record[53]=record[54]=skinned?0:-1;record[55]=flags;record[63]=2;
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,recordBuffer);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,record,GL15.GL_DYNAMIC_DRAW);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,9,recordBuffer);
        GL20.glVertexAttrib3f(0,position[0],position[1],position[2]);GL20.glVertexAttrib3f(1,0,1,0);
        GL20.glVertexAttrib4f(2,weights[0],weights[1],weights[2],weights[3]);GL20.glVertexAttrib4f(3,0,0,0,0);GL30.glVertexAttribI1i(6,0);
        GL20.glUseProgram(program);GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);GL30.glBeginTransformFeedback(GL11.GL_POINTS);GL11.glDrawArrays(GL11.GL_POINTS,0,1);GL30.glEndTransformFeedback();GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
        GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,feedback);float[] result=new float[8];GL15.glGetBufferSubData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,result);return result;
    }
    public static void main(String[] args)throws Exception{
        if(args[0].equals("--delegate")){installReader();Class.forName(args[1]).getMethod("main",String[].class).invoke(null,(Object)Arrays.copyOfRange(args,2,args.length));return;}
        Path out=Path.of(args[0]);boolean before=Boolean.parseBoolean(args[1]);
        if(!GLFW.glfwInit())throw new AssertionError("GLFW unavailable");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);long window=GLFW.glfwCreateWindow(32,32,"R116 motion contract",0,0);if(window==0)throw new AssertionError("Hidden GL unavailable");GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
        try{
            String nativeSource=source("model.vert"),irisSource=source("iris/model_vertex.glsl");
            installReader();
            int vao=GL30.glGenVertexArrays();GL30.glBindVertexArray(vao);
            int records=GL15.glGenBuffers(),palettes=GL15.glGenBuffers(),feedback=GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,palettes);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,new float[]{1,0,0,0,0,1,0,0,0,0,1,0},GL15.GL_STATIC_DRAW);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,8,palettes);
            GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,feedback);GL15.glBufferData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,32L,GL15.GL_DYNAMIC_READ);GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,feedback);
            JSONArray cases=new JSONArray();boolean staticFailure=false,irisFailure=false;double maximum=0;
            for(boolean iris:new boolean[]{false,true}){
                String raw=iris?irisSource:nativeSource,patched=source(iris?"iris/model_vertex.glsl":"model.vert");
                if(!before&&(!patched.contains("VP_PMX_DEFORM2")||!MeshVertexPatch.adapt(patched).equals(patched)))throw new AssertionError("Actual SDK reader did not install one idempotent adapter");
                Files.writeString(out.resolve(iris?"iris.vert":"native.vert"),patched);int actual=program(patched,iris),reference=program(raw,iris);
                for(int test=0;test<4;test++){
                    boolean owned=test==2,skin=test==3;float[] pos={.25f,.5f,.75f},prior={-.4f,.2f,.1f};
                    float[] weights=skin?new float[]{1,0,0,0}:test==0?new float[]{0,0,0,0}:new float[]{prior[0],prior[1],prior[2],-1e20f};
                    int flags=8|(owned?4096:0);float[] got=run(actual,flags,pos,weights,skin,records,feedback);
                    float[] wanted=run(reference,8,owned?prior:pos,new float[]{0,0,0,0},false,records,feedback);
                    double error=0;for(int i=0;i<4;i++)error=Math.max(error,Math.abs(got[i+4]-wanted[i]));maximum=Math.max(maximum,error);
                    float[] present=run(reference,8,pos,skin?weights:new float[]{0,0,0,0},skin,records,feedback);
                    for(int i=0;i<4;i++)if(Math.abs(got[i]-present[i])>2e-6)throw new AssertionError("Current geometry changed: pipeline="+iris+", case="+test);
                    if(!iris&&test==1&&error>.1)staticFailure=true;if(iris&&owned&&error>.1)irisFailure=true;
                    if(!before&&error>2e-6)throw new AssertionError((iris?"Iris":"Native")+" case="+test+" previous-position error="+error);
                    cases.put(new JSONObject().put("pipeline",iris?"iris":"native").put("case",new String[]{"static_clean","static_inherited_pmx_attribute","pmx_deformation","vanilla_skinning"}[test]).put("error",error));
                }
                GL20.glDeleteProgram(actual);GL20.glDeleteProgram(reference);
            }
            if(before&&(!staticFailure||!irisFailure))throw new AssertionError("Both old defects must reproduce: static="+staticFailure+", iris="+irisFailure);
            if(GL11.glGetError()!=0)throw new AssertionError("GL error");
            JSONObject result=new JSONObject().put("status",before?"OLD_STATIC_ATTRIBUTE_AND_IRIS_MOTION_DEFECTS_REPRODUCED":"PASS_OWNED_DEFORMATION_AND_STATIC_ISOLATION").put("cases",cases).put("max_error",maximum).put("normal_sdk_shader_reader",true).put("gpu",GL11.glGetString(GL11.GL_RENDERER)).put("game_started",false).put("nvidia_gameplay_tested",false);
            Files.writeString(out.resolve("result.json"),result.toString(2));System.out.println(result);
            GL15.glDeleteBuffers(records);GL15.glDeleteBuffers(palettes);GL15.glDeleteBuffers(feedback);GL30.glDeleteVertexArrays(vao);
        }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
