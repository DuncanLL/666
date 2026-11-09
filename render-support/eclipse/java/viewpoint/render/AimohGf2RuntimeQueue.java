package viewpoint.render;

import java.nio.IntBuffer;
import java.util.*;
import org.json.JSONObject;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Existing native draw ABI, one Java submission per source stage. */
public final class AimohGf2RuntimeQueue {
    public record Frame(float[] inverse,float[] inverseNormal,int attributeCount,int attributeTexture,
                        float[] previousModel,float[] previousInverse,float[] previousEye,float[] jitter,
                        boolean history,boolean sourceMrt){}
    public record Job(AimohGf2OriginalProgram program,JSONObject state,int draw,
                      Map<Integer,Integer> registers,Map<Integer,int[]> textures,Frame rendererFrame){
        public Job(AimohGf2OriginalProgram program,JSONObject state,int draw,Map<Integer,Integer> registers,Map<Integer,int[]> textures){this(program,state,draw,registers,textures,null);}
    }
    private Frame frame;
    private float scale=Float.NaN;
    private final Map<AimohGf2OriginalProgram,Frame> prepared=new IdentityHashMap<>();
    private final IntBuffer colorAndNormal;
    public AimohGf2RuntimeQueue(){this(true);}
    AimohGf2RuntimeQueue(boolean actorMotion){colorAndNormal=actorMotion?null:BufferUtils.createIntBuffer(2);}
    public int draw(List<Job> jobs,Frame next){
        return draw(jobs,next,1);
    }
    public int draw(List<Job> jobs,Frame next,float renderScale){
        if(frame!=next||scale!=renderScale){frame=next;scale=renderScale;prepared.clear();}
        GL13.glActiveTexture(GL13.GL_TEXTURE0+126);GL11.glBindTexture(GL11.GL_TEXTURE_2D,next.attributeTexture());
        for(Job job:jobs){
            AimohGf2OriginalProgram program=job.program();program.registers(job.registers());program.textures(job.textures());
            Frame actual=job.rendererFrame()==null?next:job.rendererFrame();
            if(prepared.put(program,actual)!=actual){
                program.nativeInputs(actual.inverse(),actual.inverseNormal(),actual.attributeCount());
                program.nativeFrame(actual.previousModel(),actual.previousInverse(),actual.previousEye(),actual.jitter(),actual.history());
                program.renderScale(renderScale);
            }
            if(next.sourceMrt()){
                program.nativeSunVisibility(AimohGf2SourceHost.color(16));
                int[] outputs=program.sourceOutputs();
                if(colorAndNormal==null)GL20.glDrawBuffers(outputs);
                else GL20.glDrawBuffers(colorAndNormal.put(0,outputs[0]).put(1,outputs[1]));
            }
            AimohGf2SourceHost.drawNative(program,job.state(),job.draw());
        }
        return jobs.size();
    }
}
