import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.lang.reflect.*;
import org.json.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import viewpoint.render.AimohGf2OriginalProgram;

/** Real source program links in a private hidden GL context; no game or model draw. */
public final class OriginalStageLinkCheck {
    private static final Pattern OUTPUT=Pattern.compile("(?m)^[ \\t]*(?:layout[ \\t]*\\(([^\\n)]*)\\)[ \\t]*)?(?:(?:flat|smooth|noperspective|centroid)[ \\t]+)*out[ \\t]+(\\w+)[ \\t]+(\\w+)[ \\t]*;");
    private static final Pattern LOCATION=Pattern.compile("\\blocation\\s*=\\s*(\\d+)");
    private static void check(String vertex,String fragment){
        Set<Integer> slots=new HashSet<>();Matcher out=OUTPUT.matcher(vertex);int count=0,normal=-1;
        while(out.find()) {
            Matcher location=LOCATION.matcher(out.group(1)==null?"":out.group(1));
            if(!location.find())throw new AssertionError("Implicit vertex output: "+out.group(3));
            int slot=Integer.parseInt(location.group(1));if(!slots.add(slot))throw new AssertionError("Duplicate vertex output slot: "+slot);
            if(out.group(3).equals("gf2NativeWorldNormal"))normal=slot;count++;
        }
        if(fragment.contains("in vec3 gf2NativeWorldNormal;")&&normal<0)throw new AssertionError("Consumed bridge normal missing");
        Matcher input=Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*in\\s+vec3\\s+gf2NativeWorldNormal;").matcher(fragment);
        if(fragment.contains("in vec3 gf2NativeWorldNormal;")&&(!input.find()||Integer.parseInt(input.group(1))!=normal))throw new AssertionError("Bridge normal locations differ");
    }
    private static String[] adapted(String vertex,String fragment)throws Exception {
        Class<?> adapter=Class.forName("viewpoint.render.AimohGf2Rc2Vertex");
        try {
            Object stages=adapter.getMethod("stages",String.class,String.class).invoke(null,vertex,fragment);
            return new String[]{(String)stages.getClass().getMethod("vertex").invoke(stages),(String)stages.getClass().getMethod("fragment").invoke(stages)};
        }catch(NoSuchMethodException old){return new String[]{(String)adapter.getMethod("adapt",String.class).invoke(null,vertex),fragment};}
    }
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[1]);Files.createDirectories(out);
        if(!GLFW.glfwInit())throw new AssertionError("GL initialization failed");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        long window=GLFW.glfwCreateWindow(32,32,"R116 original shader links",0,0);
        if(window==0)throw new AssertionError("Hidden GL context unavailable");
        try {
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            JSONArray jobs=new JSONArray(Files.readString(Path.of(args[0])));int linked=0;
            for(Object value:jobs) {
                JSONObject job=(JSONObject)value;String vertex=Files.readString(Path.of(job.getString("vertex"))),fragment=Files.readString(Path.of(job.getString("fragment")));
                String[] stages=adapted(vertex,fragment);check(stages[0],stages[1]);
                if(linked==0){Files.writeString(out.resolve("reported-program.vert"),stages[0]);Files.writeString(out.resolve("reported-program.frag"),stages[1]);}
                try(var program=new AimohGf2OriginalProgram(vertex,fragment,job.getString("vs"),job.getString("fs"))) {
                    int error=GL11.glGetError();if(error!=0)throw new AssertionError("GL error "+error+" in "+job);
                }
                linked++;System.out.println("LINKED "+linked+" "+job.getString("vs").substring(0,12)+"/"+job.getString("fs").substring(0,12)+" "+job.getString("kind"));
            }
            JSONObject result=new JSONObject().put("status","EXPLICIT_STAGE_INTERFACES_AND_REAL_GL_LINKS_PASS").put("program_pairs",linked)
                .put("gpu",GL11.glGetString(GL11.GL_RENDERER)).put("vendor",GL11.glGetString(GL11.GL_VENDOR))
                .put("opengl",GL11.glGetString(GL11.GL_VERSION)).put("game_started",false).put("live_nvidia_tested",false);
            Files.writeString(out.resolve("result.json"),result.toString(2));System.out.println(result);
        }finally{GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
}
