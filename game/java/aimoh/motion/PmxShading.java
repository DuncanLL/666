package aimoh.motion;

import com.shiroha.mmdskin.NativeFunc;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Optional PMX-MAT1 inputs; reads the final pose without advancing it. */
public final class PmxShading {
    public final int[] ids,kinds;
    private PmxShading danrpVariant;
    public final float[] uv1;
    public final float[] markerClearance;
    public final int[][] outlineSources,outlineWeld,outlineColors;
    public final float[][] outlineWeights;
    public final int[] outlineGroupCount;
    public final float[] outlineSize;
    public final float outlineBaseNative;
    public final float outlineMaxNative;
    public final float modelToken0,modelToken1;
    public final boolean danrp,shaderOutline;
    public final int fringeMeshMaterial;
    public final int[] fringeMeshSource,fringeMeshIndices;
    private static final AtomicInteger NEXT_RENDER_INSTANCE=new AtomicInteger(1);
    private final int renderInstance,vertexTextureWidth,vertexTextureHeight,vertexCount;
    private int poseSequence;
    private float[] previousPositions,previousRotations;
    private final Path[] outlineTextures;
    private final int head,bones;
    private final Vector3f origin,forward,up,radius;
    private final ByteBuffer palette;
    private final int[] fringeMaterials;
    private final float[][] fringeBaseline;
    private final int[] fringeVertices;
    private final int[][] fringeGroups;
    private final int[][] fringeRanges;
    private final Path[] fringeTextures;

    private PmxShading(JSONObject j,JSONObject profile,Path root)throws Exception {
        if(j.getInt("format")!=1||!j.getString("protocol").equals("PMX-MAT1")||!j.getString("pmx_sha256").equals(profile.getString("pmx_sha256")))
            throw new IllegalArgumentException("Shading data belongs to a different PMX");
        String modelHash=profile.getString("pmx_sha256");
        modelToken0=Integer.parseInt(modelHash.substring(0,6),16);modelToken1=Integer.parseInt(modelHash.substring(6,12),16);
        danrp=j.optInt("danrp_payload",0)==1;shaderOutline=j.optBoolean("shader_outline",false);
        renderInstance=danrp?NEXT_RENDER_INSTANCE.getAndIncrement():0;
        vertexCount=profile.getInt("vertices");
        vertexTextureWidth=j.optInt("vertex_texture_width",0);vertexTextureHeight=j.optInt("vertex_texture_height",0);
        if(danrp&&(vertexTextureWidth<=0||vertexTextureHeight<=0))throw new IllegalArgumentException("DanRP vertex texture descriptor missing");
        JSONObject caster=j.optJSONObject("danrp_fringe_mesh");
        if(danrp&&caster!=null){
            fringeMeshMaterial=caster.getInt("material");fringeMeshSource=ints(caster.getJSONArray("source_vertices"));fringeMeshIndices=ints(caster.getJSONArray("indices"));
            if(fringeMeshMaterial<0||fringeMeshMaterial>=j.getJSONArray("material_ids").length()||fringeMeshSource.length==0||fringeMeshIndices.length==0||fringeMeshIndices.length%3!=0)throw new IllegalArgumentException("Invalid DanRP fringe mesh");
            java.util.HashSet<Integer> seen=new java.util.HashSet<>();
            for(int vertex:fringeMeshSource)if(vertex<0||vertex>=vertexCount||!seen.add(vertex))throw new IllegalArgumentException("Invalid DanRP fringe source vertex");
            for(int index:fringeMeshIndices)if(index<0||index>=fringeMeshSource.length)throw new IllegalArgumentException("Invalid DanRP fringe triangle");
        }else{fringeMeshMaterial=-1;fringeMeshSource=new int[0];fringeMeshIndices=new int[0];}
        bones=profile.getInt("bones");head=j.getInt("head_bone");
        if(head<0||head>=bones||j.getLong("vertices")!=profile.getLong("vertices"))throw new IllegalArgumentException("Invalid shading skeleton reference");
        origin=vector(j,"head_origin");forward=vector(j,"head_forward").normalize();up=vector(j,"head_up").normalize();radius=vector(j,"head_radius");
        if(Math.min(radius.x,Math.min(radius.y,radius.z))<=0||Math.abs(forward.dot(up))>.01f)throw new IllegalArgumentException("Invalid shading frame");
        ids=ints(j.getJSONArray("material_ids"));kinds=ints(j.getJSONArray("material_kinds"));
        if(ids.length!=kinds.length)throw new IllegalArgumentException("Shading material count");
        outlineSources=new int[ids.length][];outlineWeld=new int[ids.length][];outlineColors=new int[ids.length][];
        outlineWeights=new float[ids.length][];outlineGroupCount=new int[ids.length];outlineSize=new float[ids.length];
        outlineTextures=new Path[ids.length];
        outlineBaseNative=(float)j.optDouble("outline_base_native",0);
        if(!Float.isFinite(outlineBaseNative)||outlineBaseNative<0)throw new IllegalArgumentException("Invalid outline scale");
        JSONArray outlines=j.optJSONArray("outline_materials");float maxOutline=0;
        if(outlines!=null&&outlines.length()>0&&outlineBaseNative<=0)throw new IllegalArgumentException("Outline scale must be positive");
        if(outlines!=null)for(int i=0;i<outlines.length();i++){
            JSONObject item=outlines.getJSONObject(i);int id=item.getInt("material");
            if(id<0||id>=ids.length||outlineSources[id]!=null)throw new IllegalArgumentException("Invalid outline material");
            int[] src=ints(item.getJSONArray("source_vertices")),weld=ints(item.getJSONArray("weld_groups")),color=ints(item.getJSONArray("edge_rgba"));
            JSONArray scales=item.getJSONArray("edge_scale");int groups=item.getInt("group_count");float width=item.getFloat("edge_size");
            if(src.length==0||weld.length!=src.length||scales.length()!=src.length||color.length!=4||groups<=0||groups>src.length||!Float.isFinite(width)||width<0)throw new IllegalArgumentException("Invalid outline geometry");
            float[] weights=new float[src.length];
            for(int k=0;k<src.length;k++){
                weights[k]=scales.getFloat(k);
                if(src[k]<0||src[k]>=profile.getInt("vertices")||weld[k]<0||weld[k]>=groups||!Float.isFinite(weights[k])||weights[k]<0)throw new IllegalArgumentException("Invalid outline vertex");
                maxOutline=Math.max(maxOutline,outlineBaseNative*width*weights[k]);
            }
            for(int c:color)if(c<0||c>255)throw new IllegalArgumentException("Invalid outline color");
            outlineSources[id]=src;outlineWeld[id]=weld;outlineColors[id]=color;outlineWeights[id]=weights;outlineGroupCount[id]=groups;outlineSize[id]=width;
            outlineTextures[id]=root.resolve(item.getString("texture")).normalize();
            if(!outlineTextures[id].startsWith(root))throw new IllegalArgumentException("Outline texture escapes model");
        }
        outlineMaxNative=maxOutline;
        markerClearance=new float[ids.length];JSONArray clearance=j.optJSONArray("marker_uv_clearance");
        if(clearance!=null){if(clearance.length()!=ids.length)throw new IllegalArgumentException("Shading UV coverage count");for(int i=0;i<ids.length;i++){markerClearance[i]=clearance.getFloat(i);if(!Float.isFinite(markerClearance[i])||markerClearance[i]<0)throw new IllegalArgumentException("Invalid shading UV coverage");}}
        for(int i=0;i<ids.length;i++)if(ids[i]<0||ids[i]>255||kinds[i]<0||kinds[i]>7)throw new IllegalArgumentException("Shading material code");
        Path file=root.resolve(j.getString("uv1")).normalize();if(!file.startsWith(root))throw new IllegalArgumentException("Shading UV path escapes model");
        byte[] bytes=Files.readAllBytes(file);
        if(bytes.length!=Math.multiplyExact(profile.getInt("vertices"),8)||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(j.getString("uv1_sha256")))
            throw new IllegalArgumentException("Shading UV data differs");
        uv1=new float[bytes.length/4];ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(uv1);
        for(float v:uv1)if(!Float.isFinite(v)||Math.abs(v)>=8)throw new IllegalArgumentException("Shading UV out of PMX-MAT1 range");
        palette=ByteBuffer.allocateDirect(Math.multiplyExact(bones,64)).order(ByteOrder.nativeOrder());
        JSONArray features=j.optJSONArray("fringe_materials"),baseline=j.optJSONArray("fringe_baseline_materials");
        fringeMaterials=features==null?new int[0]:ints(features);fringeBaseline=new float[fringeMaterials.length][12];
        if(fringeMaterials.length>0&&(baseline==null||baseline.length()!=fringeMaterials.length))throw new IllegalArgumentException("Fringe source data");
        for(int i=0;i<fringeMaterials.length;i++){
            if(fringeMaterials[i]<0||fringeMaterials[i]>=ids.length||baseline.getJSONArray(i).length()!=12)throw new IllegalArgumentException("Fringe material reference");
            for(int k=0;k<12;k++)fringeBaseline[i][k]=baseline.getJSONArray(i).getFloat(k);
        }
        JSONArray vertices=j.optJSONArray("fringe_dynamic_vertices"),groups=j.optJSONArray("fringe_dynamic_groups"),ranges=j.optJSONArray("fringe_triangle_ranges"),paths=j.optJSONArray("fringe_texture_paths");
        if((vertices==null)!=(groups==null))throw new IllegalArgumentException("Incomplete dynamic fringe data");
        if(groups!=null&&(ranges==null||ranges.length()!=groups.length()))throw new IllegalArgumentException("Incomplete fringe triangle ranges");
        fringeVertices=vertices==null?new int[0]:ints(vertices);
        fringeGroups=groups==null?new int[0][]:new int[groups.length()][];
        fringeRanges=groups==null?new int[0][]:new int[groups.length()][];
        for(int vertex:fringeVertices)if(vertex<0||vertex>=profile.getInt("vertices"))throw new IllegalArgumentException("Fringe vertex reference");
        for(int i=0;i<fringeGroups.length;i++){
            fringeGroups[i]=ints(groups.getJSONArray(i));
            if(fringeGroups[i].length==0)throw new IllegalArgumentException("Empty fringe group");
            for(int vertex:fringeGroups[i])if(vertex<0||vertex>=fringeVertices.length)throw new IllegalArgumentException("Fringe group reference");
            fringeRanges[i]=ints(ranges.getJSONArray(i));
            if(fringeRanges[i].length!=2||fringeRanges[i][0]<0||fringeRanges[i][1]<=0||fringeRanges[i][0]+fringeRanges[i][1]>j.getInt("fringe_triangle_count"))throw new IllegalArgumentException("Invalid fringe triangle range");
        }
        if(paths!=null&&paths.length()!=fringeMaterials.length)throw new IllegalArgumentException("Fringe texture reference");
        fringeTextures=paths==null?null:new Path[paths.length()];
        if(paths!=null)for(int i=0;i<paths.length();i++){
            fringeTextures[i]=root.resolve(paths.getString(i)).normalize();
            if(!fringeTextures[i].startsWith(root))throw new IllegalArgumentException("Fringe texture escapes model");
        }
    }
    public static PmxShading load(Path model,JSONObject profile)throws Exception {
        Path root=model.toAbsolutePath().getParent(),file=root.resolve("shading.json");
        PmxShading normal=Files.exists(file)?new PmxShading(new JSONObject(Files.readString(file)),profile,root):null;
        Path optional=root.resolve("shading-danrp.json");
        if(Files.exists(optional)){
            JSONObject data=new JSONObject(Files.readString(optional));
            PmxShading extra=new PmxShading(data,profile,root);
            if(!extra.danrp)throw new IllegalArgumentException("Optional shading profile is not DanRP");
            if(normal==null){
                JSONObject nativeData=new JSONObject(data.toString());
                nativeData.put("danrp_payload",0).put("shader_outline",false).put("outline_materials",new JSONArray());
                nativeData.remove("danrp_fringe_mesh");
                JSONArray kinds=new JSONArray();for(int i=0;i<data.getJSONArray("material_ids").length();i++)kinds.put(0);
                nativeData.put("material_kinds",kinds);
                normal=new PmxShading(nativeData,profile,root);
            }
            normal.danrpVariant=extra;
        }
        return normal;
    }
    private static int[] ints(JSONArray a){int[] r=new int[a.length()];for(int i=0;i<r.length;i++)r[i]=a.getInt(i);return r;}
    private static Vector3f vector(JSONObject j,String key){JSONArray a=j.getJSONArray(key);if(a.length()!=3)throw new IllegalArgumentException(key);Vector3f r=new Vector3f(a.getFloat(0),a.getFloat(1),a.getFloat(2));if(!r.isFinite())throw new IllegalArgumentException(key);return r;}
    public PmxShading renderProfile(){
        viewpoint.platform.IrisPacks.Active pack=viewpoint.platform.IrisPacks.drawn();
        if(danrpVariant!=null&&pack!=null&&"true".equalsIgnoreCase(pack.values().get("VP_DANRP_PIPELINE")))return danrpVariant;
        return this;
    }
    public float[] freeze(NativeFunc api,long handle){return freeze(api,handle,null);}
    public float[] freeze(NativeFunc api,long handle,float[] materials) {return freeze(api,handle,materials,null,null);}
    public float[] freeze(NativeFunc api,long handle,float[] materials,float[] positions,String[] textures) {
        PmxShading selected=renderProfile();
        if(selected!=this)return selected.freeze(api,handle,materials,positions,textures);
        if(danrpVariant!=null){danrpVariant.previousPositions=null;danrpVariant.previousRotations=null;}
        palette.clear();if(api.CopySkinningMatricesToBuffer(handle,palette)!=bones)throw new IllegalStateException("Incomplete shading pose");
        float[] values=new float[16];for(int i=0;i<16;i++)values[i]=palette.getFloat(head*64+i*4);
        Matrix4f m=new Matrix4f().set(values);Vector3f f=m.transformDirection(new Vector3f(forward)).normalize(),u=m.transformDirection(new Vector3f(up)).normalize(),o=m.transformPosition(new Vector3f(origin));
        boolean authored=true;
        if(materials!=null)for(int i=0;i<fringeMaterials.length;i++)for(int k=0;k<12;k++)
            if(Math.abs(materials[fringeMaterials[i]*12+k]-fringeBaseline[i][k])>1e-6f)authored=false;
        if(fringeTextures!=null&&textures!=null)for(int i=0;i<fringeMaterials.length;i++)
            if(!fringeTextures[i].equals(Path.of(textures[fringeMaterials[i]]).toAbsolutePath().normalize()))authored=false;
        boolean outlineAuthored=true;
        if(textures!=null)for(int i=0;i<outlineTextures.length;i++)if(outlineTextures[i]!=null&&!outlineTextures[i].equals(Path.of(textures[i]).toAbsolutePath().normalize()))outlineAuthored=false;
        // PMX-MAT1 row2.w: absolute radiusZ; sign marks projection validity, not geometry.
        float[] headFrame={f.x,f.y,f.z,radius.x,u.x,u.y,u.z,radius.y,o.x,o.y,o.z,authored?radius.z:-radius.z};
        if(danrp)return freezeDanrp(headFrame,positions);
        if(positions==null||fringeVertices.length==0)return headFrame;
        int floats=12+4+fringeGroups.length*8+fringeVertices.length*4;
        float[] data=new float[((floats+11)/12)*12];System.arraycopy(headFrame,0,data,0,12);
        data[12]=modelToken0;data[13]=modelToken1;data[14]=fringeVertices.length;data[15]=outlineAuthored?fringeGroups.length:-fringeGroups.length;
        int base=16+fringeGroups.length*8;Vector3f side=new Vector3f(f).cross(u).normalize();
        for(int i=0;i<fringeVertices.length;i++){
            int src=fringeVertices[i]*3,dst=base+i*4;
            float x=positions[src]-o.x,y=positions[src+1]-o.y,z=positions[src+2]-o.z;
            data[dst]=(x*side.x+y*side.y+z*side.z)/radius.x;
            data[dst+1]=(x*u.x+y*u.y+z*u.z)/radius.y;
            data[dst+2]=(x*f.x+y*f.y+z*f.z)/radius.z;
        }
        for(int group=0;group<fringeGroups.length;group++){
            int b=16+group*8;
            for(int k=0;k<3;k++){data[b+k]=Float.POSITIVE_INFINITY;data[b+4+k]=Float.NEGATIVE_INFINITY;}
            for(int vertex:fringeGroups[group])for(int k=0;k<3;k++){
                float x=data[base+vertex*4+k];data[b+k]=Math.min(data[b+k],x);data[b+4+k]=Math.max(data[b+4+k],x);
            }
            for(int k=0;k<3;k++){data[b+k]-=.0001f;data[b+4+k]+=.0001f;}
            data[b+3]=fringeRanges[group][0];data[b+7]=fringeRanges[group][1];
        }
        return data;
    }
    /** Same captured pose; adds rendering data, never advances animation or physics. */
    private float[] freezeDanrp(float[] headFrame,float[] positions) {
        viewpoint.platform.IrisPacks.Active pack=viewpoint.platform.IrisPacks.drawn();
        if(pack==null||!"true".equalsIgnoreCase(String.valueOf(pack.values().get("VP_DANRP_PIPELINE")))){
            previousPositions=null;previousRotations=null;
            float[] inactive=new float[36];System.arraycopy(headFrame,0,inactive,0,12);return inactive;
        }
        int normalsOffset=36,previousNormalOffset=normalsOffset+bones*12,previousOffset=previousNormalOffset+bones*12;
        int count=positions==null?0:positions.length;
        if(positions!=null&&count!=vertexCount*3)throw new IllegalStateException("DanRP source position count changed");
        boolean history=positions!=null&&previousPositions!=null&&previousPositions.length==count;
        float[] data=new float[((previousOffset+count+11)/12)*12];
        System.arraycopy(headFrame,0,data,0,12);
        data[12]=modelToken0;data[13]=modelToken1;data[14]=vertexCount;data[15]=1;
        data[16]=bones;data[17]=normalsOffset/4;data[18]=previousOffset;data[19]=history?1:0;
        data[20]=vertexTextureWidth;data[21]=vertexTextureHeight;data[22]=64;data[23]=ids.length;
        data[24]=renderInstance;data[25]=poseSequence;data[26]=42137;data[27]=previousNormalOffset/4;
        float[] rotations=new float[bones*12];
        for(int bone=0;bone<bones;bone++)for(int col=0;col<3;col++)for(int row=0;row<3;row++)
            rotations[bone*12+col*4+row]=data[normalsOffset+bone*12+col*4+row]=palette.getFloat(bone*64+col*16+row*4);
        System.arraycopy(history&&previousRotations!=null?previousRotations:rotations,0,data,previousNormalOffset,rotations.length);
        if(positions!=null){
            System.arraycopy(history?previousPositions:positions,0,data,previousOffset,count);
            // FrameData owns immutable arrays; retain exactly one previous snapshot, no history list.
            previousPositions=positions;previousRotations=rotations;data[25]=++poseSequence;
        }
        return data;
    }

}
