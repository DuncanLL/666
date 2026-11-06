package aimoh.motion;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Model-authored choices resolve to PMX masks, never another character model. */
public final class Wardrobe {
    private final JSONObject definition;
    private final Map<String,Set<String>> options=new HashMap<>();
    private final int materials,morphs;
    private final boolean[] managedMaterials,managedMorphs;
    public Wardrobe(String json,int materials,int morphs) {
        this.materials=materials;this.morphs=morphs;
        managedMaterials=new boolean[materials];managedMorphs=new boolean[morphs];definition=new JSONObject(json);
        if(definition.optInt("format",1)!=1)throw new IllegalArgumentException("Unsupported wardrobe format");
        JSONArray groups=definition.optJSONArray("groups");if(groups==null){groups=new JSONArray();definition.put("groups",groups);}
        for(int i=0;i<groups.length();i++) {
            JSONObject group=groups.getJSONObject(i);String id=identifier(group.getString("id"));
            if(options.containsKey(id))throw new IllegalArgumentException("Duplicate wardrobe group: "+id);
            Set<String> values=new HashSet<>();JSONArray choices=group.getJSONArray("options");
            if(choices.isEmpty())throw new IllegalArgumentException("Empty wardrobe group: "+id);
            for(int j=0;j<choices.length();j++) {
                JSONObject choice=choices.getJSONObject(j);
                if(!values.add(identifier(choice.getString("id"))))throw new IllegalArgumentException("Duplicate wardrobe option");validateAction(choice);
            }
            if(!values.contains(group.getString("default")))throw new IllegalArgumentException("Missing wardrobe default");options.put(id,values);
        }
        JSONArray rules=definition.optJSONArray("rules");if(rules==null){rules=new JSONArray();definition.put("rules",rules);}
        for(int i=0;i<rules.length();i++) {
            JSONObject rule=rules.getJSONObject(i),when=rule.getJSONObject("when");
            for(String key:when.keySet())validateChoice(key,when.getString(key));validateAction(rule);
        }
    }
    private static String identifier(String value) {
        if(value.isBlank()||value.length()>128)throw new IllegalArgumentException("Invalid wardrobe identifier");return value;
    }
    private void validateChoice(String group,String option) {
        if(!options.containsKey(group)||!options.get(group).contains(option))throw new IllegalArgumentException("Unknown wardrobe choice: "+group+"/"+option);
    }
    private void validateAction(JSONObject action) {
        JSONArray visible=action.optJSONArray("materials");
        if(visible!=null)for(int i=0;i<visible.length();i++) {
            int index=visible.getInt(i);if(index<0||index>=materials)throw new IllegalArgumentException("Wardrobe material out of range");managedMaterials[index]=true;
        }
        JSONObject weights=action.optJSONObject("morphs");
        if(weights!=null)for(String key:weights.keySet()) {
            int index=Integer.parseInt(key);double value=weights.getDouble(key);
            if(index<0||index>=morphs||!Double.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("Invalid wardrobe morph");managedMorphs[index]=true;
        }
    }
    public JSONObject choices(JSONObject requested) {
        JSONObject selected=new JSONObject();JSONArray groups=definition.getJSONArray("groups");
        for(String key:requested.keySet())validateChoice(key,requested.getString(key));
        for(int i=0;i<groups.length();i++) {
            JSONObject group=groups.getJSONObject(i);String id=group.getString("id");selected.put(id,requested.optString(id,group.getString("default")));
        }
        return selected;
    }
    private static void apply(JSONObject action,boolean[] visible,float[] weights) {
        JSONArray ids=action.optJSONArray("materials");if(ids!=null)for(int i=0;i<ids.length();i++)visible[ids.getInt(i)]=true;
        JSONObject values=action.optJSONObject("morphs");if(values!=null)for(String key:values.keySet())weights[Integer.parseInt(key)]=(float)values.getDouble(key);
    }
    public void resolve(JSONObject choices,boolean[] visible,float[] weights) {
        boolean[] mask=new boolean[materials];for(int i=0;i<materials;i++)mask[i]=!managedMaterials[i];
        for(int i=0;i<morphs;i++)if(managedMorphs[i])weights[i]=0;
        JSONArray groups=definition.getJSONArray("groups");
        for(int i=0;i<groups.length();i++)for(Object value:groups.getJSONObject(i).getJSONArray("options")) {
            JSONObject option=(JSONObject)value;
            if(option.getString("id").equals(choices.getString(groups.getJSONObject(i).getString("id"))))apply(option,mask,weights);
        }
        for(Object value:definition.getJSONArray("rules")) {
            JSONObject rule=(JSONObject)value,when=rule.getJSONObject("when");boolean matches=true;
            for(String key:when.keySet())matches&=when.getString(key).equals(choices.getString(key));if(matches)apply(rule,mask,weights);
        }
        for(int i=0;i<materials;i++)visible[i]&=mask[i];
    }
    public String json(){return definition.toString();}
    public boolean managesMorph(int index){return managedMorphs[index];}
    public static String empty(){return "{\"format\":1,\"groups\":[],\"rules\":[]}";}
}
