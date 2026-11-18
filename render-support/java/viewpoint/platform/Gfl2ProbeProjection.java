package viewpoint.platform;

import org.joml.Matrix4f;
import org.lwjgl.opengl.*;

/** Temporary GPU values for the six projection views. The owning GlProgram is never reconfigured or deleted. */
public final class Gfl2ProbeProjection {
    private final int program,eye,camera,matrix;
    private final int[] locations, previous;
    private static final int[] PROBE_VALUES={0,0,0,9,10,11,12,119,120};
    private final float[] oldEye=new float[3],oldCamera=new float[4],oldMatrix=new float[16];
    private final Matrix4f inverse=new Matrix4f();
    // Same value history as the former dedicated light program. Keep it separate
    // from the main view so the probe cannot invalidate VP's cached uniforms.
    private final Uniforms values=new Uniforms();
    public Gfl2ProbeProjection(GlProgram program){
        this.program=program.id();
        eye=location("uEye");camera=location("uCamera");matrix=location("uInvViewProjection");
        String[] names={"uDebugView","uBlobCount","uContactShadows","uAlbedo","uNormal","uEngineLight","uDepth","uReflection","uReflectionDepth"};
        locations=new int[names.length];previous=new int[names.length];
        for(int i=0;i<names.length;i++)locations[i]=location(names[i]);
    }
    int location(String name){return GL20.glGetUniformLocation(program,name);}
    public void capture(){
        if(eye>=0)GL20.glGetUniformfv(program,eye,oldEye);
        if(camera>=0)GL20.glGetUniformfv(program,camera,oldCamera);
        if(matrix>=0)GL20.glGetUniformfv(program,matrix,oldMatrix);
        for(int i=0;i<locations.length;i++)if(locations[i]>=0)previous[i]=GL20.glGetUniformi(program,locations[i]);
    }
    public void camera(float[] point){
        values.set(eye,point[0],point[1],point[2]);
        values.set(camera,.05f,400f,1f,1f);
        for(int i=0;i<3;i++)values.setInt(locations[i],PROBE_VALUES[i]);
        for(int i=3;i<locations.length;i++)GL41.glProgramUniform1i(program,locations[i],PROBE_VALUES[i]);
    }
    public void face(Matrix4f view){values.set(matrix,inverse.set(view).invert());}
    public void restore(){
        GL41.glProgramUniform3fv(program,eye,oldEye);
        GL41.glProgramUniform4fv(program,camera,oldCamera);
        GL41.glProgramUniformMatrix4fv(program,matrix,false,oldMatrix);
        for(int i=0;i<locations.length;i++)GL41.glProgramUniform1i(program,locations[i],previous[i]);
    }
}
