package viewpoint.render;

import java.util.*;
import org.json.*;
import org.joml.Matrix3d;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;

/** The reviewed native -> source coordinate/light adapter, independent of GL.
 * Model identity selects affine/root data only. No role/location/look presets.
 * GPU SH, main-light color and camera-fill color are copied by the existing
 * environment bridge after these register placeholders are uploaded.
 */
public final class AimohGf2FrameInputs {
    private final Matrix4d affine;
    private final Matrix3d bind,inverseBind;
    private final JSONObject roots,cameraFill;
    public record Inputs(Map<String,float[]> values,float[] previousInverse,int cameraFillSlot,
                         int nativeLightCount,int unsupportedCarBeams){}

    public AimohGf2FrameInputs(JSONObject model,JSONObject cameraFill){
        JSONArray a=model.getJSONArray("source_affine");
        if(a.length()!=4)throw new IllegalArgumentException("Source affine shape differs");
        for(Object row:a)if(((JSONArray)row).length()!=3)throw new IllegalArgumentException("Source affine row differs");
        affine=new Matrix4d(a.getJSONArray(0).getDouble(0),a.getJSONArray(0).getDouble(1),a.getJSONArray(0).getDouble(2),0,
            a.getJSONArray(1).getDouble(0),a.getJSONArray(1).getDouble(1),a.getJSONArray(1).getDouble(2),0,
            a.getJSONArray(2).getDouble(0),a.getJSONArray(2).getDouble(1),a.getJSONArray(2).getDouble(2),0,
            a.getJSONArray(3).getDouble(0),a.getJSONArray(3).getDouble(1),a.getJSONArray(3).getDouble(2),1);
        if(!Double.isFinite(affine.determinant())||affine.determinant()==0)throw new IllegalArgumentException("Invalid source affine");
        bind=new Matrix3d().scaling(1,1,-1).mul(new Matrix3d(affine));inverseBind=new Matrix3d(bind).invert();
        roots=model.getJSONObject("renderer_frames").getJSONObject("groups");this.cameraFill=cameraFill;
    }

    private static Matrix4d matrix(float[] value){if(value==null||value.length!=16)throw new IllegalArgumentException("Native matrix input absent");return new Matrix4d(new Matrix4f().set(value));}
    private static float[] floats(Matrix4d value){return new Matrix4f(value).get(new float[16]);}
    private Matrix4d object(float[] placement,float[] eye){
        Matrix4d result=new Matrix4d().scaling(-1,1,-1).mul(matrix(placement)).scale(1,1,-1).mul(affine);
        result.m30(result.m30()+eye[0]).m31(result.m31()-eye[1]).m32(result.m32()+eye[2]);return result;
    }

    public Inputs build(Map<String,float[]> nativeValues,float[] placement,float[] eye,
                        float[] previousPlacement,float[] previousEye,int width,int height,
                        double elapsedSeconds,boolean history,float[] points,float[] torches,float[] spots){
        if(width<=0||height<=0||eye.length!=3||!Double.isFinite(elapsedSeconds))throw new IllegalArgumentException("Invalid native frame");
        Matrix4d view=matrix(nativeValues.get("gbufferModelView")),projection=matrix(nativeValues.get("gbufferProjection")),inverseView=matrix(nativeValues.get("gbufferModelViewInverse"));
        Matrix4d current=object(placement,eye),previous=object(history?previousPlacement:placement,history?previousEye:eye);
        Matrix4d cameraToWorld=new Matrix4d(inverseView).scale(1,1,-1);
        Map<String,float[]> values=new LinkedHashMap<>();float[] light=nativeValues.get("lightDir");
        if(light==null||light.length<3)throw new IllegalArgumentException("Native main-light direction absent");
        values.put("unity_ObjectToWorld",floats(current));values.put("unity_WorldToObject",floats(new Matrix4d(current).invert()));
        values.put("unity_WorldTransformParams",new float[]{0,0,0,(float)Math.signum(current.determinant3x3())});
        values.put("unity_SpecCube0_HDR",new float[]{1,1,0,0});values.put("_MainLightPosition",new float[]{light[0],light[1],light[2],0});values.put("_MainLightColor",new float[]{0,0,0,1});
        float[] screen={width,height,(float)(1+1d/width),(float)(1+1d/height)};values.put("_ScreenParams",screen);values.put("_ScaledScreenParams",screen);
        values.put("_Time",new float[]{(float)(elapsedSeconds/20),(float)elapsedSeconds,(float)(elapsedSeconds*2),(float)(elapsedSeconds*3)});
        values.put("_WorldSpaceCameraPos",new float[]{0,0,0});values.put("glstate_matrix_projection",floats(projection));values.put("unity_MatrixVP",floats(new Matrix4d(projection).mul(view)));
        values.put("unity_MatrixV",floats(view));values.put("unity_MatrixInvV",floats(inverseView));values.put("unity_CameraToWorld",floats(cameraToWorld));values.put("_ShadowThresholdLightDirection",new float[]{light[0],light[1],light[2],0});
        values.put("GlobalCharOutlineZBias",new float[]{0});values.put("_CharUnderBGColor",new float[4]);values.put("_CharUnderBGOutlineColor",new float[4]);
        for(String name:List.of("unity_SHAr","unity_SHAg","unity_SHAb","unity_SHBr","unity_SHBg","unity_SHBb","unity_SHC"))values.put(name,new float[4]);
        Matrix4d previousVp=history?matrix(nativeValues.get("gbufferPreviousProjection")).mul(matrix(nativeValues.get("gbufferPreviousModelView"))):new Matrix4d(projection).mul(view);
        values.put("_PrevViewProjMatrix",floats(previousVp));values.put("unity_MatrixPreviousM",floats(previous));values.put("unity_MotionVectorsParams",new float[]{history?1:0,1,0,0});
        values.put("_TAAOffsets",new float[4]);values.put("_CharGlobalBloomSize",new float[]{0});
        int[] counts=localLights(values,points,torches,spots,eye,cameraToWorld);
        return new Inputs(values,floats(new Matrix4d(previous).invert()),counts[0],counts[1],counts[2]);
    }

    private record Light(float x,float y,float z,float radius,float r,float g,float b,float dx,float dy,float dz,double outer,double inner){}
    private int[] localLights(Map<String,float[]> values,float[] points,float[] torches,float[] spots,float[] eye,Matrix4d camera){
        if(points.length%8!=0||torches.length%11!=0||spots.length%24!=0)throw new IllegalArgumentException("Native light record shape differs");
        List<Light> records=new ArrayList<>();int unsupported=0;
        for(int i=0;i<torches.length;i+=11){double outer=torches[i+7];records.add(new Light(torches[i],torches[i+1],torches[i+2],torches[i+3],torches[i+8],torches[i+9],torches[i+10],torches[i+4],torches[i+5],torches[i+6],outer,outer+(1-outer)*.3));}
        for(int i=0;i<spots.length;i+=24){if((int)spots[i+7]!=0){unsupported++;continue;}records.add(new Light(spots[i],spots[i+1],spots[i+2],spots[i+3],spots[i+8],spots[i+9],spots[i+10],spots[i+4],spots[i+5],spots[i+6],spots[i+15],Math.cos(spots[i+20])));}
        for(int i=0;i<points.length;i+=8)records.add(new Light(points[i],points[i+1],points[i+2],points[i+3],points[i+4]*points[i+7],points[i+5]*points[i+7],points[i+6]*points[i+7],0,0,1,-1,-1));
        int available=records.size(),count=Math.min(available,cameraFill==null?8:7),cameraSlot=-1;
        float[] positions=new float[1024],colors=new float[1024],attenuation=new float[1024],directions=new float[1024];
        for(int i=0;i<count;i++){
            Light light=records.get(i);int at=i*4;if(light.radius<=0)throw new IllegalArgumentException("Native light radius must be positive");
            positions[at]=-(light.x-eye[0]);positions[at+1]=light.y-eye[1];positions[at+2]=-(light.z-eye[2]);positions[at+3]=1;
            colors[at]=light.r;colors[at+1]=light.g;colors[at+2]=light.b;colors[at+3]=1;
            attenuation[at]=(float)(1/((double)light.radius*light.radius));attenuation[at+3]=1;
            if(light.outer> -1){
                if(!(light.outer> -1&&light.outer<1&&light.inner>light.outer&&light.inner<=1))throw new IllegalArgumentException("Unsupported native cone");
                float length=(float)Math.sqrt(light.dx*light.dx+light.dy*light.dy+light.dz*light.dz);if(length<=1e-8)throw new IllegalArgumentException("Native light direction is zero");
                directions[at]=light.dx/length;directions[at+1]=-light.dy/length;directions[at+2]=light.dz/length;
                double scale=1/(light.inner-light.outer);attenuation[at+2]=(float)scale;attenuation[at+3]=(float)(-light.outer*scale);
            }
        }
        if(cameraFill!=null){
            cameraSlot=count;int at=count*4;JSONArray source=cameraFill.getJSONArray("camera_local_direction"),color=cameraFill.getJSONArray("color");
            Vector3d direction=new Vector3d(source.getDouble(0),source.getDouble(1),source.getDouble(2));camera.transformDirection(direction).normalize();
            positions[at]=(float)direction.x;positions[at+1]=(float)direction.y;positions[at+2]=(float)direction.z;
            for(int i=0;i<4;i++)colors[at+i]=(float)color.getDouble(i);attenuation[at+1]=1;attenuation[at+3]=1;directions[at+2]=1;count++;
        }
        values.put("unity_LightData",new float[]{0,count,1,0});values.put("unity_LightIndices",new float[]{0,1,2,3,4,5,6,7});values.put("_AdditionalLightsCount",new float[]{count,0,0,0});
        values.put("_AdditionalLightsPosition",positions);values.put("_AdditionalLightsColor",colors);values.put("_AdditionalLightsAttenuation",attenuation);values.put("_AdditionalLightsSpotDir",directions);
        return new int[]{cameraSlot,available,unsupported};
    }

    /** Existing final skin palette only; no animation/physics evaluation. */
    public double[] rootRotation(String group,float[] palettes,int header){
        JSONObject definition=roots.getJSONObject(group);int bone=definition.getInt("bone");
        if(header<0||header+26>=palettes.length||(int)palettes[header+26]!=42137||bone<0||bone>=(int)palettes[header+16])throw new IllegalArgumentException("Frozen renderer root header differs");
        int at=header+(int)palettes[header+17]*4+bone*12;if(at<0||at+11>=palettes.length)throw new IllegalArgumentException("Frozen renderer root outside palette");
        Matrix3d skin=new Matrix3d(palettes[at],palettes[at+1],palettes[at+2],palettes[at+4],palettes[at+5],palettes[at+6],palettes[at+8],palettes[at+9],palettes[at+10]);
        JSONArray r=definition.getJSONArray("bind_rotation");Matrix3d rotation=new Matrix3d(
            r.getJSONArray(0).getDouble(0),r.getJSONArray(1).getDouble(0),r.getJSONArray(2).getDouble(0),
            r.getJSONArray(0).getDouble(1),r.getJSONArray(1).getDouble(1),r.getJSONArray(2).getDouble(1),
            r.getJSONArray(0).getDouble(2),r.getJSONArray(1).getDouble(2),r.getJSONArray(2).getDouble(2));
        Matrix3d result=new Matrix3d(inverseBind).mul(skin).mul(bind).mul(rotation);
        if(!Double.isFinite(result.determinant())||Math.abs(result.determinant())<1e-8)throw new IllegalArgumentException("Invalid source renderer orientation");
        return new double[]{result.m00(),result.m01(),result.m02(),result.m10(),result.m11(),result.m12(),result.m20(),result.m21(),result.m22()};
    }
}
