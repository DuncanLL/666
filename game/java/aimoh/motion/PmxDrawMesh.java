package aimoh.motion;

import com.shiroha.mmdskin.NativeFunc;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.function.IntFunction;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import viewpoint.platform.IrisPacks;
import viewpoint.platform.ShaderPacks;
import viewpoint.platform.ShaderPack;
import java.lang.reflect.Method;
import viewpoint.render.ModelDraws;
import zombie.asset.AssetPath;
import zombie.core.skinnedmodel.model.ModelMesh;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.core.textures.Texture;

/** CPU-deformed PMX to the host's existing ModelDraws path. GL-thread only. */
public final class PmxDrawMesh implements AutoCloseable {
    /** Private draw bit, above the inspected host's BAKED bit; never inferred from disabled attributes. */
    public static final int DEFORMED = 1 << 12;
    private static final Field HANDLE,NATIVE_DEFINES;
    private static final Method NATIVE_SHADER;
    private static final String[] OUTLINE_OPTIONS={"VP_PMX_MODEL_OUTLINE","VP_PMX_SHADING","VP_NPR","VP_CHARACTER_PBR","VP_ASTERIA","VP_LAIYA","VP_DANRP_PIPELINE"};
    static {
        try {
            HANDLE = VertexBufferObject.class.getDeclaredField("handle");
            HANDLE.setAccessible(true);
            NATIVE_SHADER=ShaderPack.class.getDeclaredMethod("shader",String.class);NATIVE_SHADER.setAccessible(true);
            NATIVE_DEFINES=ShaderPacks.Active.class.getDeclaredField("defines");NATIVE_DEFINES.setAccessible(true);
        } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    private final NativeFunc api;
    private final long model;
    private final ModelAppearance appearance;
    private final PmxShading shading;
    private final Texture[] renderTextures;
    private volatile PmxDrawMesh alternateMesh;
    private final String sourceProfile;
    private final ByteBuffer positions, normals, uv;
    private final ArrayList<Part> parts = new ArrayList<>();
    private final Vector3f point = new Vector3f();
    private volatile boolean uploaded, closed;
    private IrisPacks.Active lastOutlinePack;
    private boolean outlineSupported;
    private ShaderPacks.Active lastNativeOutlinePack;
    private boolean nativeOutlineSupported;

    /** Arrays belong to this frame and are never mutated after capture. */
    public static final class FrameData {
        private final float[] positions,normals,materials,shadingHead;
        private final float minX,minY,minZ,maxX,maxY,maxZ;
        public FrameData(float[] positions,float[] normals,float[] materials){this(positions,normals,materials,null);}
        public FrameData(float[] positions,float[] normals,float[] materials,float[] shadingHead) {this(positions,normals,materials,shadingHead,null,null);}
        private FrameData(float[] positions,float[] normals,float[] materials,float[] shadingHead,FrameData a,FrameData b) {
            if (positions.length!=normals.length || positions.length%3!=0 || materials.length%12!=0)
                throw new IllegalArgumentException("Invalid frozen PMX frame");
            if(shadingHead!=null&&(shadingHead.length<12||shadingHead.length%12!=0))throw new IllegalArgumentException("Invalid shading frame");
            if(shadingHead!=null)for(float value:shadingHead)if(!Float.isFinite(value))throw new IllegalArgumentException("Non-finite shading frame");
            float lx=Float.POSITIVE_INFINITY,ly=lx,lz=lx,hx=-lx,hy=hx,hz=hx;
            for(int i=0;i<positions.length;i+=3){
                float x=positions[i],y=positions[i+1],z=positions[i+2];
                if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z))throw new IllegalArgumentException("Non-finite frozen PMX frame");
                if(a==null){lx=Math.min(lx,x);ly=Math.min(ly,y);lz=Math.min(lz,z);hx=Math.max(hx,x);hy=Math.max(hy,y);hz=Math.max(hz,z);}
            }
            for(float value:normals)if(!Float.isFinite(value))throw new IllegalArgumentException("Non-finite frozen PMX frame");
            for(float value:materials)if(!Float.isFinite(value))throw new IllegalArgumentException("Non-finite frozen PMX frame");
            this.positions=positions;this.normals=normals;this.materials=materials;this.shadingHead=shadingHead;
            minX=a==null?lx:Math.min(a.minX,b.minX);minY=a==null?ly:Math.min(a.minY,b.minY);minZ=a==null?lz:Math.min(a.minZ,b.minZ);
            maxX=a==null?hx:Math.max(a.maxX,b.maxX);maxY=a==null?hy:Math.max(a.maxY,b.maxY);maxZ=a==null?hz:Math.max(a.maxZ,b.maxZ);
        }
        /** Convex display interpolation stays inside the union of its two validated snapshots. */
        public static FrameData interpolated(float[] p,float[] n,float[] m,float[] h,FrameData a,FrameData b){return new FrameData(p,n,m,h,a,b);}
        public float[] positions(){return positions;}
        public float[] normals(){return normals;}
        public float[] materials(){return materials;}
        public float[] shadingHead(){return shadingHead;}
        public void includeBounds(Vector3f low,Vector3f high){
            low.x=Math.min(low.x,minX);low.y=Math.min(low.y,minY);low.z=Math.min(low.z,minZ);
            high.x=Math.max(high.x,maxX);high.y=Math.max(high.y,maxY);high.z=Math.max(high.z,maxZ);
        }
    }

    private static ByteBuffer buffer(int size) {
        return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
    }

    private static final class Part {
        final int material;
        final PmxOriginalInputs.Draw original;
        final int[] source, indices;
        final Texture texture;
        final ModelMesh mesh;
        final ByteBuffer vertices;
        VertexBufferObject.Vbo handle;
        float r, g, b, alpha;
        boolean additiveTint;
        final int cull;
        final int stride;
        final boolean hull,fringe;
        final int[] weld;
        final float[] edgeWeights,smooth;
        float inkU,inkV;
        volatile boolean hullReady;
        boolean continuous;
        Part(int material, int[] source, int[] indices, Texture texture, boolean bothFaces, int ordinal, boolean shading) {
            this(material,source,indices,texture,bothFaces,ordinal,shading,null,null,0);
        }
        Part(int material,int[] source,int[] indices,Texture texture,boolean bothFaces,int ordinal,boolean shading,int[] weld,float[] edgeWeights,int groups) {
            this(material,source,indices,texture,bothFaces,ordinal,shading,weld,edgeWeights,groups,false);
        }
        Part(int material,int[] source,int[] indices,Texture texture,boolean bothFaces,int ordinal,boolean shading,int[] weld,float[] edgeWeights,int groups,boolean fringe) {
            this(material,source,indices,texture,bothFaces,ordinal,shading,weld,edgeWeights,groups,fringe,null);
        }
        Part(int material,int[] source,int[] indices,Texture texture,boolean bothFaces,int ordinal,boolean shading,int[] weld,float[] edgeWeights,int groups,boolean fringe,PmxOriginalInputs.Draw original) {
            this.original=original;
            this.fringe=fringe;
            this.material = material; this.source = source; this.indices = indices;
            this.texture = texture;
            this.hull=weld!=null;this.weld=weld;this.edgeWeights=edgeWeights;this.smooth=hull?new float[groups*3]:null;
            cull = fringe ? 1029 : hull ? 1028 : bothFaces ? 0 : 1029;
            ModelMesh.MeshAssetParams params = new ModelMesh.MeshAssetParams();
            params.isStatic = true;
            mesh = new ModelMesh(new AssetPath("aimoh:pmx-part-"+(original==null?"":"gf2-"+original.sourceMaterial()+"-")+ordinal), null, params);
            VertexBufferObject vbo;
            var format=new VertexBufferObject.VertexFormat(7);
            format.setElement(0,VertexBufferObject.VertexType.VertexArray,12);format.setElement(1,VertexBufferObject.VertexType.NormalArray,12);
            format.setElement(2,VertexBufferObject.VertexType.ColorArray,4);format.setElement(3,VertexBufferObject.VertexType.TextureCoordArray,8);
            int extra=4;format.setElement(extra++,VertexBufferObject.VertexType.TextureCoordArray,8);
            format.setElement(extra++,VertexBufferObject.VertexType.BlendWeightArray,16);format.setElement(extra,VertexBufferObject.VertexType.BlendIndexArray,16);format.calculate();
            var array=new VertexBufferObject.VertexArray(format,source.length);vbo=new VertexBufferObject(array,indices);vertices=array.buffer;stride=76;
            vbo.isStatic = true;
            mesh.SetVertexBuffer(vbo);

        }
    }

    public PmxDrawMesh(NativeFunc api, long model, Texture[] textures) {
        this(api,model,textures,new ModelAppearance(api,model));
    }

    public PmxDrawMesh(NativeFunc api,long model,Texture[] textures,ModelAppearance appearance){this(api,model,textures,appearance,null);}
    public PmxDrawMesh(NativeFunc api, long model, Texture[] textures, ModelAppearance appearance,PmxShading shading) {
        this.api = api; this.model = model;
        this.appearance=appearance;this.shading=shading;this.renderTextures=textures;
        if(shading!=null&&shading.ids.length!=textures.length)throw new IllegalArgumentException("Shading material count differs");
        int count = Math.toIntExact(api.GetVertexCount(model));
        if (count <= 0 || textures.length != api.GetMaterialCount(model))
            throw new IllegalArgumentException("PMX material/vertex count mismatch");
        positions = buffer(Math.multiplyExact(count, 12));
        normals = buffer(positions.capacity()); uv = buffer(Math.multiplyExact(count, 8));
        api.CopyDataToByteBuffer(uv, api.GetUVs(model), uv.capacity());
        int size = Math.toIntExact(api.GetIndexElementSize(model));
        if (size != 1 && size != 2 && size != 4) throw new IllegalArgumentException("PMX index size");
        int indexCount = Math.toIntExact(api.GetIndexCount(model));
        ByteBuffer indices = buffer(Math.multiplyExact(indexCount, size));
        api.CopyDataToByteBuffer(indices, api.GetIndices(model), indices.capacity());
        PmxOriginalInputs original=PmxOriginalInputs.load(shading,count,textures.length);
        sourceProfile=original==null?null:original.key;
        if(original!=null){
            int ordinal=0;
            for(PmxOriginalInputs.Draw draw:original.draws)parts.add(new Part(draw.material(),draw.source(),draw.indices(),textures[draw.material()],api.GetMaterialBothFace(model,draw.material()),ordinal++,true,null,null,0,false,draw));
        }
        for (int n = 0; original==null&&n < api.GetSubMeshCount(model); n++) {
            int material = api.GetSubMeshMaterialID(model, n);
            int first = api.GetSubMeshBeginIndex(model, n), length = api.GetSubMeshVertexCount(model, n);
            if (material < 0 || material >= textures.length || first < 0 || length < 0 ||
                length % 3 != 0 || (long)first + length > indexCount)
                throw new IllegalArgumentException("Invalid PMX submesh range");
            LinkedHashMap<Integer, Integer> unique = new LinkedHashMap<>();
            int[] local = new int[length];
            for (int i = 0; i < length; i++) {
                int at = (first + i) * size;
                int id = size == 1 ? Byte.toUnsignedInt(indices.get(at)) :
                    size == 2 ? Short.toUnsignedInt(indices.getShort(at)) : indices.getInt(at);
                if (id < 0 || id >= count) throw new IllegalArgumentException("Invalid PMX vertex index");
                local[i] = unique.computeIfAbsent(id, key -> unique.size());
            }
            int[] source=unique.keySet().stream().mapToInt(Integer::intValue).toArray();
            parts.add(new Part(material,source,local,textures[material],api.GetMaterialBothFace(model,material),n,shading!=null));
            if(shading!=null&&shading.outlineSources[material]!=null){
                int[] lookup=new int[count];Arrays.fill(lookup,-1);int[] authored=shading.outlineSources[material];
                for(int i=0;i<authored.length;i++)lookup[authored[i]]=i;
                int[] weld=new int[source.length];float[] weights=new float[source.length];
                for(int i=0;i<source.length;i++){
                    int at=lookup[source[i]];if(at<0)throw new IllegalArgumentException("Outline vertex not in source material");
                    weld[i]=shading.outlineWeld[material][at];weights[i]=shading.outlineWeights[material][at];
                }
                // The source winding is retained: the actual host culls its front
                // faces, leaving the thin back-facing shell around the actor.
                parts.add(new Part(material,source,local,textures[material],true,n+10000,true,weld,weights,shading.outlineGroupCount[material]));
            }
        }
        if(original==null&&shading!=null&&shading.danrp&&shading.fringeMeshSource.length>0)
            parts.add(new Part(shading.fringeMeshMaterial,shading.fringeMeshSource,shading.fringeMeshIndices,textures[shading.fringeMeshMaterial],false,20000,true,null,null,0,true));
        updateOutlineTextures(textures);
    }

    private PmxDrawMesh renderMesh(){
        PmxShading selected=shading==null?null:shading.renderProfile();
        String key=PmxOriginalInputs.key(selected);
        if(selected==shading&&java.util.Objects.equals(sourceProfile,key))return this;
        if(alternateMesh!=null&&(alternateMesh.shading!=selected||!java.util.Objects.equals(alternateMesh.sourceProfile,key))){PmxDrawMesh old=alternateMesh;alternateMesh=null;viewpoint.render.Retirement.retire(old::close);}
        if(alternateMesh==null)alternateMesh=new PmxDrawMesh(api,model,renderTextures,appearance,selected);
        return alternateMesh;
    }
    /** Capture never creates GPU resources or publishes an unuploaded topology. */
    public PmxDrawMesh readyMesh(){
        PmxShading selected=shading==null?null:shading.renderProfile();String key=PmxOriginalInputs.key(selected);
        PmxDrawMesh candidate=selected==shading&&java.util.Objects.equals(sourceProfile,key)?this:alternateMesh;
        return candidate!=null&&candidate.shading==selected&&java.util.Objects.equals(candidate.sourceProfile,key)&&candidate.uploaded&&!candidate.closed?candidate:null;
    }
    public void updateOutlineTextures(Texture[] textures){
        if(alternateMesh!=null)alternateMesh.updateOutlineTextures(textures);
        if(shading==null)return;
        for(Part part:parts)if(part.hull){
            Texture texture=textures[part.material];int w=texture.getWidth(),h=texture.getHeight();
            part.hullReady=shading.shaderOutline||w>=8&&h>=8&&shading.markerClearance[part.material]>8f/Math.min(w,h);
            part.inkU=4.5f/w;part.inkV=4.5f/h;
        }
    }

    private synchronized boolean outlineEnabled(){
        if(shading==null)return false;
        IrisPacks.Active active=IrisPacks.drawn();
        if(active==null){
            lastOutlinePack=null;outlineSupported=false;
            ShaderPacks.Active nativePack=ShaderPacks.drawn();if(nativePack==null)return false;
            try {
                if(nativePack!=lastNativeOutlinePack){lastNativeOutlinePack=nativePack;Object source=NATIVE_SHADER.invoke(nativePack.pack,"lib/vp_pmx_data.glsl");nativeOutlineSupported=source instanceof String&&((String)source).contains("#define PMX_SHELL_ABI 1");}
                if(!nativeOutlineSupported)return false;
                String defines=(String)NATIVE_DEFINES.get(nativePack);
                return !defines.contains("#define OPTION_CHARACTERS 0")&&!defines.contains("#define OPTION_PMX 0")&&!defines.contains("#define OPTION_PMX_MODEL_OUTLINE 0");
            }catch(ReflectiveOperationException ignored){return false;}
        }
        if(active!=lastOutlinePack){
            lastOutlinePack=active;outlineSupported=false;
            try {outlineSupported=active.pack().source.read("lib/vp_pmx_data.glsl").contains("#define PMX_SHELL_ABI 1");}
            catch(Exception ignored){outlineSupported=false;}
        }
        if(!outlineSupported)return false;
        for(String key:OUTLINE_OPTIONS){
            Object value=active.values().get(key);if(value!=null&&value.toString().equalsIgnoreCase("false"))return false;
        }
        return true;
    }

    public void upload() {
        PmxDrawMesh selected=renderMesh();if(selected!=this){selected.upload();return;}
        if (closed) throw new IllegalStateException("Closed mesh stream");
        api.CopyDataToByteBuffer(positions, api.GetPoss(model), positions.capacity());
        api.CopyDataToByteBuffer(normals, api.GetNormals(model), normals.capacity());
        for (int i = 0; i < positions.capacity(); i += 4)
            if (!Float.isFinite(positions.getFloat(i)) || !Float.isFinite(normals.getFloat(i)))
                throw new IllegalArgumentException("Non-finite PMX geometry");
        uploadBuffers(appearance.readMaterials());
    }

    public void upload(FrameData frame) {
        PmxDrawMesh selected=renderMesh();if(selected!=this){selected.upload(frame);return;}
        uploadPrepared(frame);
    }
    /** An already captured command keeps the topology it was submitted with. */
    public void uploadPrepared(FrameData frame) {uploadPrepared(frame,appearance.readMaterials(false));}
    public void uploadPrepared(FrameData frame,float[] shadowMaterials) {
        if (closed || frame.positions.length*4!=positions.capacity()) throw new IllegalArgumentException("Frozen mesh size differs");
        for (int i=0;i<frame.positions.length;i++) {
            positions.putFloat(i*4,frame.positions[i]); normals.putFloat(i*4,frame.normals[i]);
        }
        uploadBuffers(frame.materials,shadowMaterials);
    }

    private void uploadBuffers(float[] materialState){uploadBuffers(materialState,appearance.readMaterials(false));}
    private void uploadBuffers(float[] materialState,float[] shadowMaterials) {
        if(shadowMaterials.length!=materialState.length)throw new IllegalArgumentException("Shadow material count differs");
        boolean useOutline=outlineEnabled();
        for (Part part : parts) {
            if(part.hull&&(!useOutline||!part.hullReady)){part.continuous=false;continue;}
            int offset=part.material*12;
            part.r=materialState[offset]*materialState[offset+4];
            part.g=materialState[offset+1]*materialState[offset+5];
            part.b=materialState[offset+2]*materialState[offset+6];
            part.alpha=materialState[offset+3]*Math.clamp(materialState[offset+7]+materialState[offset+11],0,1);
            part.additiveTint=false;
            for (int k=8;k<11;k++) if (materialState[offset+k]!=0)
                part.additiveTint=true;
            if (!Float.isFinite(part.alpha) || !Float.isFinite(part.r) ||
                !Float.isFinite(part.g) || !Float.isFinite(part.b))
                throw new IllegalArgumentException("Non-finite PMX material");
            boolean colour=colourVisible(part,materialState),shadow=shadowVisible(part,shadowMaterials);
            // Pack-owned eye layers can ignore base alpha; keep them ready across queued appearance changes.
            if(part.original!=null&&part.original.sourceAlphaIndependent()&&materialState[offset+7]+materialState[offset+11]>0)colour=true;
            if(!colour&&((part.hull||part.fringe)||!shadow)){part.continuous=false;continue;}
            part.vertices.clear();
            part.mesh.minXyz.set(Float.POSITIVE_INFINITY);
            part.mesh.maxXyz.set(Float.NEGATIVE_INFINITY);
            if(part.hull&&!shading.shaderOutline){
                Arrays.fill(part.smooth,0);
                for(int i=0;i<part.source.length;i++){
                    int source=part.source[i],g=part.weld[i]*3;float x=normals.getFloat(source*12),y=normals.getFloat(source*12+4),z=normals.getFloat(source*12+8);
                    float length=(float)Math.sqrt(x*x+y*y+z*z);if(length>1e-8f){part.smooth[g]+=x/length;part.smooth[g+1]+=y/length;part.smooth[g+2]+=z/length;}
                }
                for(int g=0;g<part.smooth.length;g+=3){float x=part.smooth[g],y=part.smooth[g+1],z=part.smooth[g+2],length=(float)Math.sqrt(x*x+y*y+z*z);if(length>1e-8f){part.smooth[g]/=length;part.smooth[g+1]/=length;part.smooth[g+2]/=length;}}
            }
            for (int i=0;i<part.source.length;i++) {
                int source=part.source[i];
                float x = positions.getFloat(source * 12), y = positions.getFloat(source * 12 + 4),
                    z = positions.getFloat(source * 12 + 8);
                if(part.hull&&!shading.shaderOutline){
                    int g=part.weld[i]*3;float nx=part.smooth[g],ny=part.smooth[g+1],nz=part.smooth[g+2];
                    if(nx*nx+ny*ny+nz*nz<.25f){nx=normals.getFloat(source*12);ny=normals.getFloat(source*12+4);nz=normals.getFloat(source*12+8);}
                    float width=shading.outlineBaseNative*shading.outlineSize[part.material]*part.edgeWeights[i];x+=nx*width;y+=ny*width;z+=nz*width;
                }
                // PMX-DEFORM1: previous rendered local position; the negative weight flag never invokes skinning.
                float px=!part.continuous?x:part.vertices.getFloat(i*part.stride),py=!part.continuous?y:part.vertices.getFloat(i*part.stride+4),pz=!part.continuous?z:part.vertices.getFloat(i*part.stride+8);
                part.vertices.putFloat(x).putFloat(y).putFloat(z);
                float sign=part.hull&&!shading.shaderOutline?-1f:1f;
                part.vertices.putFloat(normals.getFloat(source * 12)*sign)
                    .putFloat(normals.getFloat(source * 12 + 4)*sign).putFloat(normals.getFloat(source * 12 + 8)*sign);
                part.vertices.putInt(-1).putFloat(part.hull&&!shading.shaderOutline?part.inkU:uv.getFloat(source * 8)).putFloat(part.hull&&!shading.shaderOutline?part.inkV:uv.getFloat(source * 8 + 4));
                if(part.original!=null){
                    int attribute=part.original.attributes()[i];
                    part.vertices.putFloat(2048f+(64+part.original.sourceMaterial())*32f+16f+(attribute%512)/64f-7.5f)
                        .putFloat(shading.kinds[part.material]*32f+16f+(attribute/512)/64f-2f);
                }else if(shading!=null)part.vertices.putFloat(2048f+shading.ids[part.material]*32f+16f+(part.hull&&!shading.shaderOutline?0:shading.uv1[source*2])).putFloat(part.fringe?-2000f+shading.uv1[source*2+1]:part.hull?-1000f+(shading.shaderOutline?shading.uv1[source*2+1]:0):shading.kinds[part.material]*32f+16f+shading.uv1[source*2+1]);
                else part.vertices.putFloat(0).putFloat(0); // Plain materials must not inherit a previous draw's generic UV2 attribute.
                part.vertices.putFloat(px).putFloat(py).putFloat(pz).putFloat(-1e20f);
                part.vertices.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
                point.set(x, y, z);
                part.mesh.minXyz.min(point); part.mesh.maxXyz.max(point);
            }
            part.vertices.flip();
            part.handle = part.mesh.vb.LoadSoftwareVBO(part.vertices, part.handle, part.indices);
            part.handle.vertexStride=part.stride;
            try { HANDLE.set(part.mesh.vb, part.handle); }
            catch (IllegalAccessException e) { throw new IllegalStateException(e); }
            part.mesh.modificationCount++;
            part.continuous=true;
        }
        uploaded = true;
    }

    private boolean colourVisible(Part part,float[] m){int at=part.material*12;return(m[at+3]>0||(part.original!=null&&part.original.sourceAlphaIndependent()&&appearance.gf2SourceZeroAlphaVisible(part.material)))&&m[at+7]+m[at+11]>0;}
    private static boolean shadowVisible(Part part,float[] m){int at=part.material*12;return m[at+3]>0&&m[at+7]+m[at+11]>0;}

    public int submit(ModelDraws draws, Matrix4f placement) {
        PmxDrawMesh selected=renderMesh();if(selected!=this){return selected.submit(draws,placement);}
        if (!uploaded || closed) throw new IllegalStateException("Mesh not ready");
        int first = draws.count();
        boolean useOutline=outlineEnabled();
        for (Part part : parts) {
            if(part.fringe)continue;
            if(part.hull&&(!useOutline||!part.hullReady))continue;
            if (part.alpha <= .01f) continue;
            if (part.additiveTint) throw new IllegalStateException("Host additive texture tint requires baked textures");
            if (part.texture == null || !part.texture.isReady()) throw new IllegalStateException("Texture not ready");
            // shortcut: host has no material-opacity scalar; validate fractional opacity before production use.
            if (part.alpha < .99f) throw new IllegalStateException("Host material-opacity contract needs adaptation");
        }
        for (Part part : parts) {
            if(part.fringe)continue;
            if(part.hull&&(!useOutline||!part.hullReady))continue;
            if (part.alpha <= .01f) continue;
            int n = draws.add(part.mesh, null, part.texture, placement, -1, -1, part.cull, (part.hull?1:3)|DEFORMED);
            draws.material(n, part.r, part.g, part.b, 0);
            draws.light(n, 1, 1, 1, 0);
            draws.bounds(n, placement, part.mesh.minXyz, part.mesh.maxXyz, 1);
            draws.key(n, part);
        }
        return draws.count() - first;
    }

    /** Main-thread commands reference immutable frame data; GL uploads happen in ModelPass.prepare. */
    public int submitFrozen(ModelDraws draws,Matrix4f placement,FrameData frame,Object frameKey,float[] shadowMaterials) {
        return submitFrozen(draws,placement,frame,material->frameKey,shadowMaterials,null);
    }

    public int submitFrozen(ModelDraws draws,Matrix4f placement,FrameData frame,IntFunction<Object> frameKeys,float[] shadowMaterials,boolean[] doubleSided) {
        PmxDrawMesh selected=renderMesh();if(selected!=this){return selected.submitFrozen(draws,placement,frame,frameKeys,shadowMaterials,doubleSided);}
        return submitPreparedFrozen(draws,placement,frame,frameKeys,shadowMaterials,doubleSided);
    }
    public int submitPreparedFrozen(ModelDraws draws,Matrix4f placement,FrameData frame,IntFunction<Object> frameKeys,float[] shadowMaterials,boolean[] doubleSided) {
        if (!uploaded || closed) throw new IllegalStateException("Mesh not ready");
        for (Part part:parts) if (part.texture==null || !part.texture.isReady()) throw new IllegalStateException("Texture not ready");
        Vector3f low=new Vector3f(Float.POSITIVE_INFINITY),high=new Vector3f(Float.NEGATIVE_INFINITY);
        frame.includeBounds(low,high);
        int first=draws.count();
        int shadingPalette=frame.shadingHead==null?-1:draws.palette(frame.shadingHead,frame.shadingHead.length/12);
        boolean useOutline=outlineEnabled()&&frame.shadingHead!=null&&frame.shadingHead.length>15&&frame.shadingHead[12]==shading.modelToken0&&frame.shadingHead[13]==shading.modelToken1&&frame.shadingHead[15]>0;
        IrisPacks.Active fringePack=IrisPacks.drawn();
        boolean useFringe=shading!=null&&shading.danrp&&frame.shadingHead!=null&&frame.shadingHead.length>15&&frame.shadingHead[12]==shading.modelToken0&&frame.shadingHead[13]==shading.modelToken1&&frame.shadingHead[15]==1&&fringePack!=null&&"true".equalsIgnoreCase(String.valueOf(fringePack.values().get("VP_DANRP_PIPELINE")));
        Vector3f outlineLow=new Vector3f(low),outlineHigh=new Vector3f(high);
        if(useOutline){float pad=shading.outlineMaxNative;outlineLow.sub(pad,pad,pad);outlineHigh.add(pad,pad,pad);}
        for (Part part:parts) {
            if(part.fringe&&!useFringe)continue;
            if(part.hull&&(!useOutline||!part.hullReady))continue;
            boolean colour=colourVisible(part,frame.materials),shadow=shadowVisible(part,shadowMaterials);
            if((part.hull||part.fringe)&&!colour)continue;
            if(!colour&&!shadow)continue;
            int cull=part.fringe?1029:part.hull?1028:doubleSided==null?part.cull:doubleSided[part.material]?0:1029;
            int flags=(part.hull||part.fringe)?(colour?1:0):(colour?1:0)|(shadow?2:0);
            int n=draws.add(part.mesh,null,part.texture,placement,shadingPalette,shadingPalette,cull,flags|DEFORMED);
            draws.material(n,1,1,1,0);draws.light(n,1,1,1,0);
            draws.bounds(n,placement,part.hull?outlineLow:low,part.hull?outlineHigh:high,1);draws.key(n,frameKeys.apply(part.material));
        }
        return draws.count()-first;
    }

    public int partCount() { PmxDrawMesh selected=renderMesh();return selected==this?parts.size():selected.partCount(); }
    public int compactVertexCount() { PmxDrawMesh selected=renderMesh();return selected==this?parts.stream().mapToInt(p -> p.source.length).sum():selected.compactVertexCount(); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        AutoCloseable[] owned=new AutoCloseable[parts.size()+1];owned[0]=alternateMesh;alternateMesh=null;
        for(int i=0;i<parts.size();i++){Part part=parts.get(i);owned[i+1]=part.mesh::clear;}
        ResourceCleanup.close(owned);
    }
}
