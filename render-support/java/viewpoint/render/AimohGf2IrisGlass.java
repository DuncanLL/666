package viewpoint.render;
import java.util.*;
import java.util.regex.*;
import org.joml.Matrix4f;
import org.lwjgl.opengl.*;
import viewpoint.platform.GlProgram;

/** Native VP rain/wipers over completed Eclipse glass; no second base shading. */
public final class AimohGf2IrisGlass {
    private static final Map<IrisTargets,State> STATES=new IdentityHashMap<>();
    private static boolean drawing;
    public static void modelState(){if(drawing){GL11.glDepthMask(false);GL11.glDepthFunc(GL11.GL_LEQUAL);}}
    private static final class State {
        int texture,width,height,format,frames;
        final GlProgram program=AimohGf2NativeShaders.glass();
        State(){if(program.id()==0)throw new IllegalStateException("Native Eclipse glass program failed to link");VehiclePass.samplers(program);GlassWaterPass.samplers(program);ForwardLight.samplers(program);program.sampler("uWindscreenMeasure",60);}
        void close(){if(texture!=0)GL11.glDeleteTextures(texture);program.delete();}
    }
    /** The old corpse path and new VP torch code share this exact same map. */
    public static String fragment(String source){
        Matcher m=Pattern.compile("(?m)^\\s*uniform\\s+sampler2DArrayShadow\\s+uLampShadow\\s*;").matcher(source);
        boolean seen=false;StringBuffer output=new StringBuffer();
        while(m.find()){m.appendReplacement(output,Matcher.quoteReplacement(seen?"\n":m.group()));seen=true;}
        return m.appendTail(output).toString();
    }
    public static void draw(IrisPipeline p,FrameContext f,ShadowPass shadows,ModelPass models){
        if(models.draws().vehicles.count()==0||WorldRenderer.translucent==null)return;
        State s=STATES.computeIfAbsent(p.targets,k->new State());
        try(AimohGf2GlScope ignored=new AimohGf2GlScope(true)){
            GlassWaterPass water=WorldRenderer.translucent.water;models.water(f,water);
            int source=p.targets.buffers[3].current();int format=GL45.glGetTextureLevelParameteri(source,0,GL11.GL_TEXTURE_INTERNAL_FORMAT);
            if(s.texture==0||s.width!=f.width||s.height!=f.height||s.format!=format){
                if(s.texture!=0)GL11.glDeleteTextures(s.texture);s.texture=GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
                GL45.glTextureStorage2D(s.texture,1,format,f.width,f.height);GL45.glTextureParameteri(s.texture,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR);GL45.glTextureParameteri(s.texture,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);
                GL45.glTextureParameteri(s.texture,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);GL45.glTextureParameteri(s.texture,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
                s.width=f.width;s.height=f.height;s.format=format;
            }
            GL43.glCopyImageSubData(source,GL11.GL_TEXTURE_2D,0,0,0,0,s.texture,GL11.GL_TEXTURE_2D,0,0,0,0,f.width,f.height,1);
            var gl=s.program;gl.use();FrameUniforms.apply(gl,f);gl.set("uViewProjection",new Matrix4f(p.uniforms.projection).mul(f.view));
            float[] jitter=AimohGf2Runtime.clipJitter(p,f.width,f.height);gl.set("uEclipseGlassJitter",jitter[0],jitter[1]);
            gl.set("uEye",f.eyeX,f.eyeY,f.eyeZ);gl.set("uCameraFocus",f.scene.cameraFocus);gl.set("uGlassAlpha",0f);
            ForwardLight.uniforms(gl,f,shadows);water.glassUniforms(gl);water.bind(s.texture);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,p.targets.framebuffer(List.of(3),false,1,-1));GL11.glViewport(0,0,f.width,f.height);
            GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LEQUAL);GL11.glDepthMask(false);GL11.glDisable(GL11.GL_BLEND);GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glColorMask(true,true,true,true);drawing=true;
            try{shadows.bind(true);models.irisVehicles(gl,true);}finally{drawing=false;shadows.bind(false);water.unbind();}
            s.frames++;
        }
    }
    public static void release(IrisPipeline p){State s=STATES.remove(p.targets);if(s!=null)s.close();}
    public static String evidence(IrisPipeline p){State s=STATES.get(p.targets);return s==null?"{}":new org.json.JSONObject().put("frames",s.frames).put("width",s.width).put("height",s.height).put("program",s.program.id()).toString();}
}
