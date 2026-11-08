package viewpoint.render;
import java.util.*;
import org.json.*;
import org.lwjgl.opengl.*;

/** Original shader sampler ownership is independent of the texture being sampled. */
public final class AimohGf2OriginalSamplers implements AutoCloseable {
    record State(int filter,int u,int v,int w,float bias,int anisotropy){}
    private final JSONObject textures;
    private final Map<State,Integer> objects=new LinkedHashMap<>();
    public AimohGf2OriginalSamplers(JSONObject textures){this.textures=textures;}
    static State state(JSONObject settings){return new State(settings.getInt("m_FilterMode"),settings.getInt("m_WrapU"),settings.getInt("m_WrapV"),settings.getInt("m_WrapW"),(float)settings.getDouble("m_MipBias"),Math.max(1,settings.getInt("m_Aniso")));}
    static State resolve(JSONObject program,JSONObject material,JSONObject textures,int sampler){
        if(sampler==255)return null;
        JSONObject inline=null;JSONArray fixed=program.optJSONArray("samplers");
        if(fixed!=null)for(Object v:fixed){JSONObject entry=(JSONObject)v;if(entry.getInt("bindPoint")==sampler){if(inline!=null)throw new IllegalArgumentException("Ambiguous original inline sampler");inline=entry;}}
        if(inline!=null){
            // Original serialized 0x55: actual RDC shows linear min/mag, point mip, clamp UVW.
            if(inline.getInt("sampler")!=85)throw new IllegalArgumentException("Unreviewed original inline sampler state: "+inline.getInt("sampler"));
            return new State(1,1,1,1,0,1);
        }
        State found=null;boolean owner=false;
        for(Object v:program.getJSONArray("textures")){
            JSONObject texture=(JSONObject)v;if(texture.getInt("m_SamplerIndex")!=sampler)continue;owner=true;String name=texture.getString("name");State next;
            if(name.equals("unity_SpecCube0"))next=new State(2,1,1,1,0,1);
            else if(name.equals("_MainLightShadowmapTexture"))return null;
            else{
                JSONObject slot=material.getJSONObject("texture_bindings").optJSONObject(name);
                if(slot!=null&&slot.has("resource"))next=state(textures.getJSONObject(slot.getString("resource")).getJSONObject("metadata").getJSONObject("m_TextureSettings"));
                else next=new State(1,0,0,0,0,1); // Existing one-level Unity default texture sampling; no invented material pixels.
            }
            if(found!=null&&!found.equals(next))throw new IllegalArgumentException("Conflicting original sampler owners: s"+sampler);found=next;
        }
        if(!owner)throw new IllegalArgumentException("Original sampler metadata missing for s"+sampler+"; update the character program export");return found;
    }
    public int sampler(JSONObject program,JSONObject material,int registerKey){
        State state=resolve(program,material,textures,registerKey&255);if(state==null)return 0;Integer existing=objects.get(state);if(existing!=null)return existing;
        int object=GL33.glGenSamplers();
        try{
            int min=switch(state.filter){case 0->GL11.GL_NEAREST_MIPMAP_NEAREST;case 1->GL11.GL_LINEAR_MIPMAP_NEAREST;case 2->GL11.GL_LINEAR_MIPMAP_LINEAR;default->throw new IllegalArgumentException("Original sampler filter differs");};
            GL33.glSamplerParameteri(object,GL11.GL_TEXTURE_MIN_FILTER,min);GL33.glSamplerParameteri(object,GL11.GL_TEXTURE_MAG_FILTER,state.filter==0?GL11.GL_NEAREST:GL11.GL_LINEAR);
            GL33.glSamplerParameteri(object,GL11.GL_TEXTURE_WRAP_S,AimohGf2OriginalTexture.wrap(state.u));GL33.glSamplerParameteri(object,GL11.GL_TEXTURE_WRAP_T,AimohGf2OriginalTexture.wrap(state.v));GL33.glSamplerParameteri(object,GL12.GL_TEXTURE_WRAP_R,AimohGf2OriginalTexture.wrap(state.w));GL33.glSamplerParameterf(object,GL14.GL_TEXTURE_LOD_BIAS,state.bias);
            if(GL.getCapabilities().GL_EXT_texture_filter_anisotropic)GL33.glSamplerParameterf(object,EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT,Math.min(state.anisotropy,GL11.glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT)));
            objects.put(state,object);return object;
        }catch(RuntimeException e){GL33.glDeleteSamplers(object);throw e;}
    }
    public int count(){return objects.size();}
    @Override public void close(){for(int object:objects.values())GL33.glDeleteSamplers(object);objects.clear();}
}
