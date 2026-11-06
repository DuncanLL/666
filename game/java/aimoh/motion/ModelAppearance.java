package aimoh.motion;

import com.shiroha.mmdskin.NativeFunc;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** One validated appearance state shared by the workbench and every live PMX instance. */
public final class ModelAppearance {
    private final NativeFunc api;
    private final long model;
    private final float[] base;
    private final boolean[] originalBothFaces;
    private final String[] originalTextures;
    private final ByteBuffer morphBuffer;
    private final int[] morphIds;
    private boolean[] visible,effectiveVisible;
    private float[] alpha,weights;
    private float[][] colours;
    private Boolean[] bothFaces;
    private String[] textures;
    private Wardrobe wardrobe;
    private JSONObject selected=new JSONObject();
    private JSONArray groups=new JSONArray();
    private long revision;

    public ModelAppearance(NativeFunc api,long model) {
        this.api=api;this.model=model;
        int count=Math.toIntExact(api.GetMaterialCount(model));
        base=new float[count*4];visible=new boolean[count];Arrays.fill(visible,true);effectiveVisible=visible.clone();
        alpha=new float[count];Arrays.fill(alpha,Float.NaN);colours=new float[count][];bothFaces=new Boolean[count];textures=new String[count];
        originalBothFaces=new boolean[count];originalTextures=new String[count];
        weights=new float[Math.toIntExact(api.GetMorphCount(model))];morphIds=new int[weights.length];
        for(int i=0;i<weights.length;i++)morphIds[i]=i;
        ByteBuffer rgba=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        for(int i=0;i<count;i++) {
            api.CopyDataToByteBuffer(rgba,api.GetMaterialDiffuse(model,i),16);
            for(int k=0;k<4;k++)base[i*4+k]=rgba.getFloat(k*4);
            originalBothFaces[i]=api.GetMaterialBothFace(model,i);originalTextures[i]=api.GetMaterialTex(model,i);
        }
        morphBuffer=ByteBuffer.allocateDirect(Math.multiplyExact(count,56*4)).order(ByteOrder.nativeOrder());
        wardrobe=new Wardrobe(Wardrobe.empty(),count,weights.length);
    }
    private static JSONArray array(JSONObject state,String key,int length) {
        JSONArray value=state.getJSONArray(key);
        if(value.length()!=length)throw new IllegalArgumentException("Appearance "+key+" count differs from PMX");return value;
    }
    private static JSONArray optional(JSONObject state,String key,int length) {
        if(state.has(key))return array(state,key,length);
        JSONArray value=new JSONArray();for(int i=0;i<length;i++)value.put(JSONObject.NULL);return value;
    }
    private static float weight(JSONArray values,int index) {
        double value=values.getDouble(index);
        if(!Double.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("Appearance value must be between 0 and 1");return (float)value;
    }
    public synchronized void configure(String json){configure(json,wardrobe.json());}
    public synchronized void configure(String json,String definition) {
        JSONObject state=new JSONObject(json);int format=state.getInt("format");
        if(format!=1&&format!=2)throw new IllegalArgumentException("Unsupported appearance format");
        Wardrobe nextWardrobe=new Wardrobe(definition,visible.length,weights.length);
        JSONArray v=array(state,"material_visibility",visible.length),a=array(state,"alpha_overrides",alpha.length),w=array(state,"morph_weights",weights.length),
            c=optional(state,"colour_overrides",visible.length),b=optional(state,"double_sided",visible.length),t=optional(state,"texture_overrides",visible.length);
        boolean[] nextVisible=new boolean[visible.length];float[] nextAlpha=new float[alpha.length],nextWeights=new float[weights.length];
        float[][] nextColours=new float[visible.length][];Boolean[] nextBoth=new Boolean[visible.length];String[] nextTextures=new String[visible.length];
        for(int i=0;i<visible.length;i++) {
            nextVisible[i]=v.getBoolean(i);nextAlpha[i]=a.isNull(i)?Float.NaN:weight(a,i);
            if(!c.isNull(i)) {
                JSONArray colour=c.getJSONArray(i);if(colour.length()!=3)throw new IllegalArgumentException("Expected RGB colour");
                nextColours[i]=new float[]{weight(colour,0),weight(colour,1),weight(colour,2)};
            }
            if(!b.isNull(i))nextBoth[i]=b.getBoolean(i);
            if(!t.isNull(i)) {nextTextures[i]=t.getString(i);if(nextTextures[i].isBlank())throw new IllegalArgumentException("Empty texture override");}
        }
        for(int i=0;i<weights.length;i++)nextWeights[i]=weight(w,i);
        JSONObject nextSelected=nextWardrobe.choices(state.optJSONObject("choices")==null?new JSONObject():state.getJSONObject("choices"));
        boolean[] resolved=nextVisible.clone();float[] resolvedWeights=nextWeights.clone();nextWardrobe.resolve(nextSelected,resolved,resolvedWeights);
        JSONArray nextGroups=state.optJSONArray("groups")==null?new JSONArray():new JSONArray(state.getJSONArray("groups").toString());
        boolean changed=!Arrays.equals(visible,nextVisible)||!Arrays.equals(alpha,nextAlpha)||!Arrays.equals(weights,nextWeights)||!Arrays.deepEquals(colours,nextColours)
            ||!Arrays.equals(bothFaces,nextBoth)||!Arrays.equals(textures,nextTextures)||!selected.similar(nextSelected)||!new JSONObject(wardrobe.json()).similar(new JSONObject(nextWardrobe.json()));
        // Full zero weights clear old manual morphs. Current transport uses the game's animation source.
        if(changed) {
            api.SetPZAnimationState(model,new long[0],new float[0],new boolean[0],morphIds,resolvedWeights);
            for(int i=0;i<resolved.length;i++)api.SetMaterialVisible(model,i,resolved[i]);
            revision++;
        }
        visible=nextVisible;effectiveVisible=resolved;alpha=nextAlpha;weights=nextWeights;colours=nextColours;bothFaces=nextBoth;textures=nextTextures;
        wardrobe=nextWardrobe;selected=nextSelected;groups=nextGroups;
    }
    public synchronized void choose(String group,String option) {
        JSONObject state=new JSONObject(stateJson());state.getJSONObject("choices").put(group,option);configure(state.toString());
    }
    public synchronized String stateJson() {
        JSONArray a=new JSONArray(),c=new JSONArray(),b=new JSONArray(),t=new JSONArray();
        for(int i=0;i<alpha.length;i++) {
            a.put(Float.isNaN(alpha[i])?JSONObject.NULL:alpha[i]);c.put(colours[i]==null?JSONObject.NULL:new JSONArray(colours[i]));
            b.put(bothFaces[i]==null?JSONObject.NULL:bothFaces[i]);t.put(textures[i]==null?JSONObject.NULL:textures[i]);
        }
        return new JSONObject().put("format",2).put("material_visibility",new JSONArray(visible)).put("alpha_overrides",a).put("morph_weights",new JSONArray(weights))
            .put("colour_overrides",c).put("double_sided",b).put("texture_overrides",t).put("choices",new JSONObject(selected.toString())).put("groups",new JSONArray(groups.toString())).toString();
    }
    public synchronized String controlsJson(){return wardrobe.json();}
    public synchronized long revision(){return revision;}
    public synchronized boolean bothFaces(int material){return bothFaces[material]==null?originalBothFaces[material]:bothFaces[material];}
    public synchronized boolean[] readBothFaces(){boolean[] result=new boolean[visible.length];for(int i=0;i<result.length;i++)result[i]=bothFaces(i);return result;}
    public synchronized String texture(int material){return textures[material]==null?originalTextures[material]:textures[material];}
    public synchronized String[] readTextures(){String[] result=new String[visible.length];for(int i=0;i<result.length;i++)result[i]=texture(i);return result;}
    public synchronized boolean managesMorph(int index){return wardrobe.managesMorph(index);}
    public synchronized float[] readMaterials(){return read(true,true);}
    public synchronized float[] readTextureFactors(){return read(false,false);}
    public synchronized boolean materialAvailable(int index) {
        boolean[] mask=new boolean[visible.length];Arrays.fill(mask,true);
        wardrobe.resolve(selected,mask,new float[weights.length]);
        float[] f=readTextureFactors();int at=index*12;
        return mask[index]&&f[at+3]>.001f&&f[at+7]+f[at+11]>.001f;
    }
    /** Twelve floats/material: diffuse RGBA, texture multiply RGBA, texture add RGBA. */
    public synchronized float[] readMaterials(boolean cameraMask){return read(true,cameraMask);}
    private float[] read(boolean visibilityMask,boolean cameraMask) {
        int count=api.CopyMaterialMorphResultsToBuffer(model,morphBuffer);
        if(count!=0&&count!=visible.length)throw new IllegalStateException("Native material morph count differs from PMX");
        float[] result=new float[visible.length*12];
        for(int i=0;i<visible.length;i++) {
            int offset=i*56*4;
            for(int k=0;k<4;k++) {
                float original=k<3&&colours[i]!=null?colours[i][k]:k==3&&!Float.isNaN(alpha[i])?alpha[i]:base[i*4+k];
                result[i*12+k]=count==0?original:original*morphBuffer.getFloat(offset+k*4)+morphBuffer.getFloat(offset+(28+k)*4);
                result[i*12+4+k]=count==0?1:morphBuffer.getFloat(offset+(16+k)*4);result[i*12+8+k]=count==0?0:morphBuffer.getFloat(offset+(44+k)*4);
            }
            if((visibilityMask&&!effectiveVisible[i])||(cameraMask&&!api.IsMaterialVisible(model,i)))result[i*12+3]=0;
        }
        for(float value:result)if(!Float.isFinite(value))throw new IllegalStateException("Non-finite PMX material morph");return result;
    }
    public synchronized boolean gf2SourceZeroAlphaVisible(int material) {
        if (effectiveVisible[material] && base[material*4+3]==0
                && (Float.isNaN(alpha[material]) || !(alpha[material]<=0)))
            return api.IsMaterialVisible(model,material);
        return false;
    }
}
