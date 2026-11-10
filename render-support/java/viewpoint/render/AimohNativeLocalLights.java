package viewpoint.render;
import java.nio.FloatBuffer;
import java.util.*;
import org.lwjgl.opengl.*;
import viewpoint.platform.GlProgram;

/** Native emitters map to source additional slots; no material-facing term is applied twice. */
final class AimohNativeLocalLights {
    final float[] points;
    final int[] kinds,indices;
    AimohNativeLocalLights(List<float[]> records,List<Integer> kinds,List<Integer> indices){
        points=new float[records.size()*8];for(int i=0;i<records.size();i++)System.arraycopy(records.get(i),0,points,i*8,8);
        this.kinds=kinds.stream().mapToInt(Integer::intValue).toArray();this.indices=indices.stream().mapToInt(Integer::intValue).toArray();
    }
    AimohNativeLocalLights select(float[] sphere){
        if(sphere.length!=4||sphere[3]<0)throw new IllegalArgumentException("Native actor light bounds differ");for(float v:sphere)if(!Float.isFinite(v))throw new IllegalArgumentException("Non-finite actor light bounds");
        int[] selected=new int[7];double[] scores=new double[7];int count=0;
        for(int i=0;i<indices.length;i++){
            int p=i*8;double dx=points[p]-sphere[0],dy=points[p+1]-sphere[1],dz=points[p+2]-sphere[2],reach=points[p+3],distance=Math.max(0,Math.sqrt(dx*dx+dy*dy+dz*dz)-sphere[3]);
            if(reach<=0||distance>=reach)continue;
            double brightness=Math.max(points[p+4],Math.max(points[p+5],points[p+6]))*points[p+7],window=1-distance/reach,score=brightness*window*window;
            if(!(score>0))continue;
            int at=0;while(at<count&&scores[at]>=score)at++;if(at==7)continue;
            for(int j=Math.min(count,6);j>at;j--){selected[j]=selected[j-1];scores[j]=scores[j-1];}selected[at]=i;scores[at]=score;count=Math.min(count+1,7);
        }
        List<float[]> records=new ArrayList<>(count);List<Integer> selectedKinds=new ArrayList<>(count),selectedIndices=new ArrayList<>(count);
        for(int at=0;at<count;at++){int i=selected[at];records.add(Arrays.copyOfRange(points,i*8,i*8+8));selectedKinds.add(kinds[i]);selectedIndices.add(indices[i]);}
        return new AimohNativeLocalLights(records,selectedKinds,selectedIndices);
    }
    static AimohNativeLocalLights capture(FrameContext frame)throws Exception{
        List<float[]> records=new ArrayList<>();List<Integer> kinds=new ArrayList<>(),indices=new ArrayList<>();
        float[] torches=frame.scene.torches;
        for(int i=0;i<java.lang.Math.min(frame.scene.torchCount,8);i++){int p=i*11;records.add(new float[]{torches[p],torches[p+1],torches[p+2],torches[p+3],torches[p+8],torches[p+9],torches[p+10],1});kinds.add(1);indices.add(i);}
        Object spots=AimohNativeGf2Environment.field(AimohNativeGf2Environment.world("shadows"),"spots");int count=(Integer)AimohNativeGf2Environment.field(spots,"count");FloatBuffer values=((FloatBuffer)AimohNativeGf2Environment.field(spots,"records")).duplicate();
        for(int i=0;i<count;i++){int p=i*24;records.add(new float[]{values.get(p),values.get(p+1),values.get(p+2),values.get(p+3),values.get(p+8),values.get(p+9),values.get(p+10),1});kinds.add(2);indices.add(i);}
        if(!WorldRenderer.off(3))for(int i=0;i<java.lang.Math.min(frame.scene.lightCount,48);i++)if(frame.scene.lightKeys[i]>=0){int p=i*12;records.add(Arrays.copyOfRange(frame.scene.lights,p,p+8));kinds.add(0);indices.add(i);}
        return new AimohNativeLocalLights(records,kinds,indices);
    }
    void neutralAttenuation(Map<String,float[]> values){
        float[] attenuation=values.get("_AdditionalLightsAttenuation");
        for(int i=0;i<kinds.length;i++){Arrays.fill(attenuation,i*4,i*4+4,0);attenuation[i*4+3]=1;}
    }
    void bind(GlProgram gl,FrameContext frame)throws Exception{
        if(kinds.length>7)throw new IllegalStateException("Native local lights must be selected for the actor before binding");
        if(!gl.has("gf2NativeLocalCount"))return;gl.use();gl.setInt("gf2NativeLocalCount",kinds.length);
        if(kinds.length==0)return;
        GL20.glUniform1iv(GL20.glGetUniformLocation(gl.id(),"gf2NativeLocalKind"),kinds);GL20.glUniform1iv(GL20.glGetUniformLocation(gl.id(),"gf2NativeLocalIndex"),indices);
        AimohWorkshopRender.uniforms(gl,frame);SurfacePass.lamps(gl,frame.scene);SurfacePass.torches(gl,frame.scene);
        ShadowPass shadows=(ShadowPass)AimohNativeGf2Environment.world("shadows");shadows.lamps.uniforms(gl,frame.scene);shadows.muzzle.uniforms(gl);shadows.spots.uniforms(gl);
        LampShadows.sampler(gl);Spots.sampler(gl);shadows.bind(true);
    }
}
