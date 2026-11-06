import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;
import org.json.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

/** Runs the production clip transport on the GPU against the exact pack geometry mapping. */
public final class EclipseScreenCheck {
    static int shader(int kind,String text){int id=GL20.glCreateShader(kind);GL20.glShaderSource(id,text);GL20.glCompileShader(id);if(GL20.glGetShaderi(id,GL20.GL_COMPILE_STATUS)==0)throw new AssertionError(GL20.glGetShaderInfoLog(id));return id;}
    static int program(String body,String helper){
        int vs=shader(GL20.GL_VERTEX_SHADER,"#version 430\nlayout(location=0)in vec4 point;out vec4 captured;uniform vec2 gf2ClipJitter;uniform float gf2ClipScale=1.0;\n#define RENDER_SCALE vec2(gf2ClipScale)\n"+helper+"\nvoid main(){gl_Position=point;"+body+"\ncaptured=gl_Position;}");
        int p=GL20.glCreateProgram();GL20.glAttachShader(p,vs);GL30.glTransformFeedbackVaryings(p,new CharSequence[]{"captured"},GL30.GL_INTERLEAVED_ATTRIBS);GL20.glLinkProgram(p);GL20.glDeleteShader(vs);
        if(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)==0)throw new AssertionError(GL20.glGetProgramInfoLog(p));return p;
    }
    static float[] run(int program,float scale,float jitter,int count){
        GL20.glUseProgram(program);GL20.glUniform1f(GL20.glGetUniformLocation(program,"gf2ClipScale"),scale);GL20.glUniform2f(GL20.glGetUniformLocation(program,"gf2ClipJitter"),jitter,-jitter);
        GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);GL30.glBeginTransformFeedback(GL11.GL_POINTS);GL11.glDrawArrays(GL11.GL_POINTS,0,count);GL30.glEndTransformFeedback();GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
        float[] data=new float[count*4];GL15.glGetBufferSubData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,data);return data;
    }
    static double extent(float[] data){double lo=1e20,hi=-1e20;for(int i=0;i<data.length;i+=4){double x=data[i]/data[i+3];lo=Math.min(lo,x);hi=Math.max(hi,x);}return hi-lo;}
    public static void main(String[] args)throws Exception{
        boolean expectBug=Boolean.parseBoolean(args[4]);Path out=Path.of(args[3]);Files.createDirectories(out);
        if(!GLFW.glfwInit())throw new AssertionError("No GLFW");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);long window=GLFW.glfwCreateWindow(32,32,"Eclipse screen parity",0,0);if(window==0)throw new AssertionError("No hidden context");GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
        try{
            String vs=Files.readString(Path.of(args[0])),fs=Files.readString(Path.of(args[1]));Object stages=Class.forName("viewpoint.render.AimohGf2Rc2Vertex").getMethod("adapt",String.class,String.class).invoke(null,vs,fs);
            String adapted=(String)stages.getClass().getMethod("vertex").invoke(stages);int start=adapted.lastIndexOf("gf2OriginalVertex();")+"gf2OriginalVertex();".length(),end=adapted.indexOf("if(hiddenVertex)",start);if(start<20||end<start)throw new AssertionError("Clip boundary changed");String body=adapted.substring(start,end),helper="";
            Class<?> screen=null;try{screen=Class.forName("viewpoint.render.AimohGf2Screen");helper=(String)screen.getMethod("glsl").invoke(null);}catch(ClassNotFoundException old){}
            if(!expectBug){if(screen==null)throw new AssertionError("Shared clip transport absent");Method scale=screen.getMethod("scale",Map.class);if((float)scale.invoke(null,Map.of("TAA","false","TAA_UPSCALING","true","SCALE_FACTOR","0.5"))!=1||(float)scale.invoke(null,Map.of("TAA","true","TAA_UPSCALING","false"))!=1||(float)scale.invoke(null,Map.of("TAA","true","TAA_UPSCALING","true","SCALE_FACTOR","0.5"))!=.5f)throw new AssertionError("Effective scale options differ");}
            String reference=Files.readString(Path.of(args[2]))+"\ngl_Position.xy+=gf2ClipJitter*gl_Position.w;";
            int actual=program(body,helper),world=program(reference,"");float[] points={-.8f,-.7f,0,1,.8f,.7f,0,1,0,0,0,1,-1.6f,.6f,0,2,1.6f,-.6f,0,2};int count=points.length/4;
            int vao=GL30.glGenVertexArrays();GL30.glBindVertexArray(vao);int vertices=GL15.glGenBuffers();GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vertices);GL15.glBufferData(GL15.GL_ARRAY_BUFFER,points,GL15.GL_STATIC_DRAW);GL20.glEnableVertexAttribArray(0);GL20.glVertexAttribPointer(0,4,GL11.GL_FLOAT,false,16,0L);
            int feedback=GL15.glGenBuffers();GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,feedback);GL15.glBufferData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,points.length*4L,GL15.GL_DYNAMIC_READ);GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER,0,feedback);
            JSONArray cases=new JSONArray();double maximum=0;
            for(float scale:new float[]{1,.9f,.75f,.5f,.35f,.1f})for(float jitter:new float[]{0,.00037f}){
                float[] a=run(actual,scale,jitter,count),b=run(world,scale,jitter,count);double error=0;for(int i=0;i<a.length;i++)error=Math.max(error,Math.abs(a[i]-b[i]));maximum=Math.max(maximum,error);
                if(!expectBug&&error>2e-6)throw new AssertionError("Screen mismatch scale="+scale+" error="+error);
                cases.put(new JSONObject().put("scale",scale).put("jitter",jitter).put("max_clip_error",error).put("apparent_width_ratio_vs_world",extent(a)/extent(b)));
            }
            if(expectBug&&maximum<.1)throw new AssertionError("Old scale defect did not reproduce");
            if(GL11.glGetError()!=0)throw new AssertionError("GL error");
            JSONObject result=new JSONObject().put("status",expectBug?"OLD_SCREEN_SCALE_MISMATCH_REPRODUCED":"PRODUCTION_CLIP_TRANSPORT_MATCHES_PACK_GEOMETRY").put("cases",cases).put("max_error",maximum).put("gpu",GL11.glGetString(GL11.GL_RENDERER)).put("nvidia_gameplay_tested",false).put("game_started",false);
            Files.writeString(out.resolve("screen-parity.json"),result.toString(2));System.out.println(result);
            GL20.glDeleteProgram(actual);GL20.glDeleteProgram(world);GL15.glDeleteBuffers(vertices);GL15.glDeleteBuffers(feedback);GL30.glDeleteVertexArrays(vao);
        }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
