package aimoh.pmxpreview;

import aimoh.motion.WorldPoseRetargeter.GripAdjustment;
import java.lang.ref.WeakReference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.joml.Matrix4f;
import org.json.JSONObject;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.vm.KahluaTable;
import zombie.ZomboidFileSystem;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.HandWeapon;
import zombie.inventory.types.WeaponType;

/** Per-save, per-character calibration keyed by the held item's full type. */
final class GripCalibration {
    private static final String STORE="AimohR116GripCalibration";
    private static final int POSITION_AXES=6, ITEM_AXIS=12, CURL_AXIS=15, COUNT=17;
    private static final long TRANSITION_NANOS=150_000_000L;
    private static final Map<IsoGameCharacter,AimBlend> blends=Collections.synchronizedMap(new WeakHashMap<>());

    private static final class AimBlend {
        final String modelId;
        final WeakReference<InventoryItem> item;
        private long started;
        private float from;
        private boolean aim;

        AimBlend(String modelId,InventoryItem item,boolean aim,long now) {
            this.modelId=modelId;this.item=new WeakReference<>(item);this.aim=aim;
            from=aim?1:0;started=now;
        }

        float advance(boolean target,long now) {
            float progress=Math.clamp((now-started)/(float)TRANSITION_NANOS,0,1);
            float smooth=progress*progress*(3-2*progress);
            float current=from+((aim?1:0)-from)*smooth;
            if(target!=aim){
                from=current;
                aim=target;
                started=now;
            }
            return current;
        }
    }

    private record Character(String key,List<String> skins) {}
    private static volatile Map<String,Character> characters;

    static synchronized void loadCharacters(Path registry) throws IOException {
        var packs=new JSONObject(Files.readString(registry)).optJSONObject("packs");
        var bySkin=new HashMap<String,Character>();
        if(packs!=null)for(String key:packs.keySet()){
            var ids=packs.getJSONArray(key).toList().stream().map(Object::toString).toList();
            var character=new Character(key,ids);
            for(String id:ids)bySkin.put(id,character);
        }
        characters=Map.copyOf(bySkin);
    }

    private static Character character(String id) {
        var bySkin=characters;
        if(bySkin==null){
            String mod=ZomboidFileSystem.instance.getModDir(Main.MOD_ID);
            if(mod==null)return null;
            Path root=Path.of(mod);
            Path registry=root.resolve("media/aimoh_pmx/registry.json");
            if(!Files.isRegularFile(registry))registry=root.resolve("42/media/aimoh_pmx/registry.json");
            try{loadCharacters(registry);}
            catch(IOException | RuntimeException failure){
                System.err.println("[GFL2Exilium] Grip calibration registry: "+failure);
                characters=Map.of();
            }
            bySkin=characters;
        }
        return bySkin.get(id);
    }

    private static int limit(int axis){return axis>=CURL_AXIS?100:axis>=ITEM_AXIS?20:axis>=POSITION_AXES?90:12;}

    static int heldSide(IsoGameCharacter actor) {
        if(!(actor instanceof IsoPlayer player)||!player.isLocalPlayer())return -1;
        if(actor.getPrimaryHandItem()!=null)return 1;
        return actor.getSecondaryHandItem()!=null?0:-1;
    }

    static InventoryItem heldItem(IsoGameCharacter actor) {
        int side=heldSide(actor);
        return side==1?actor.getPrimaryHandItem():side==0?actor.getSecondaryHandItem():null;
    }

    static String itemKey(IsoGameCharacter actor) {
        InventoryItem item=heldItem(actor);
        return item==null?"":item.getFullType();
    }

    private static KahluaTable table(KahluaTable parent,String key,boolean create) {
        if(parent==null)return null;
        if(parent.rawget(key) instanceof KahluaTable child)return child;
        if(!create)return null;
        KahluaTable child=J2SEPlatform.getInstance().newTable();
        parent.rawset(key,child);
        return child;
    }

    private static KahluaTable legacy(KahluaTable model,InventoryItem item) {
        if(!(item instanceof HandWeapon weapon)||!weapon.isRanged())return null;
        String family=(weapon.isTwoHandWeapon()?WeaponType.FIREARM:WeaponType.HANDGUN).getType();
        return table(model,family,false);
    }

    private static Object savedRow(KahluaTable mode,InventoryItem item,boolean aiming) {
        if(mode==null)return null;
        Object saved=mode.rawget(item.getFullType());
        return saved==null&&!aiming?legacy(mode,item):saved;
    }

    private static Object characterRow(KahluaTable store,String id,InventoryItem item,boolean aiming) {
        if(store==null)return null;
        Character character=character(id);
        if(character==null)return savedRow(table(store,id,false),item,aiming);
        String weapon=item.getFullType();
        KahluaTable shared=table(store,character.key(),false);
        Object saved=shared==null?null:shared.rawget(weapon);
        if(saved!=null)return saved;

        // Preserve the current skin's earlier edits before looking at its siblings.
        saved=savedRow(table(store,id,false),item,aiming);
        if(saved==null)for(String skin:character.skins()){
            if(skin.equals(id))continue;
            saved=savedRow(table(store,skin,false),item,aiming);
            if(saved!=null)break;
        }
        if(saved instanceof KahluaTable old){
            KahluaTable copy=J2SEPlatform.getInstance().newTable();
            for(int axis=0;axis<COUNT;axis++){
                double v=value(old,axis);
                if(v!=0)copy.rawset(axis+1,Double.valueOf(v));
            }
            saved=copy;
        }
        if(saved!=null)table(store,character.key(),true).rawset(weapon,saved);
        return saved;
    }

    static KahluaTable currentRow(IsoGameCharacter actor,String id,InventoryItem item,boolean aiming) {
        var store=table(actor.getModData(),aiming?STORE+"Aim":STORE,false);
        return characterRow(store,id,item,aiming) instanceof KahluaTable row?row:null;
    }

    private static double value(KahluaTable row,int axis) {
        Object raw=row==null?null:row.rawget(axis+1);
        return raw instanceof Number n&&Double.isFinite(n.doubleValue())&&Math.abs(n.doubleValue())<=limit(axis)?n.doubleValue():0;
    }

    static KahluaTable sliders(IsoGameCharacter actor,String id,String key,boolean aiming) {
        InventoryItem item=heldItem(actor);
        KahluaTable row=item!=null&&key.equals(item.getFullType())?currentRow(actor,id,item,aiming):null;
        KahluaTable values=J2SEPlatform.getInstance().newTable();
        for(int i=0;i<COUNT;i++)values.rawset(i+1,Double.valueOf(value(row,i)));
        return values;
    }

    static boolean set(IsoGameCharacter actor,String id,String key,int axis,double offset,boolean aiming) {
        InventoryItem item=heldItem(actor);
        if(item==null||!item.getFullType().equals(key))return false;
        if(axis!=-1&&(axis<0||axis>=COUNT||!Double.isFinite(offset)||Math.abs(offset)>limit(axis)))return false;
        KahluaTable previous=currentRow(actor,id,item,aiming);
        if(axis!=-1&&value(previous,axis)==offset)return true;
        KahluaTable next=J2SEPlatform.getInstance().newTable();
        boolean edited=false;
        if(axis!=-1)for(int i=0;i<COUNT;i++){
            double v=i==axis?offset:value(previous,i);
            if(v!=0){next.rawset(i+1,Double.valueOf(v));edited=true;}
        }
        Character character=character(id);
        KahluaTable store=table(actor.getModData(),aiming?STORE+"Aim":STORE,true);
        KahluaTable saved=table(store,character==null?id:character.key(),true);
        if(edited)saved.rawset(key,next);
        else saved.rawset(key,character!=null||!aiming&&legacy(saved,item)!=null?Boolean.FALSE:null);
        return true;
    }

    static Matrix4f handPlacement(AnimationPlayer pose,int side) {
        var matrix=new Matrix4f();
        ModelInstanceRenderData.makeBoneTransform(pose,side==1?"Bip01_Prop1":"Bip01_Prop2",matrix);
        return matrix;
    }

    static GripAdjustment request(IsoGameCharacter actor,String id,Matrix4f weaponMatrix,AnimationPlayer pose) {
        InventoryItem item=heldItem(actor);
        if(pose==null||item==null||actor.getVariableBoolean("isLoading")||actor.getVariableBoolean("isRacking")){
            blends.remove(actor);
            return null;
        }
        String reload=actor.getVariableString("WeaponReloadType");
        if(reload!=null&&!reload.isEmpty()){
            blends.remove(actor);
            return null;
        }
        KahluaTable standing=currentRow(actor,id,item,false);
        KahluaTable aiming=currentRow(actor,id,item,true);
        if(standing==null&&aiming==null){
            blends.remove(actor);
            return null;
        }
        if(weaponMatrix==null)weaponMatrix=handPlacement(pose,heldSide(actor));
        if(standing==aiming){
            blends.remove(actor);
            return adjustment(actor,item,weaponMatrix,standing,standing,0);
        }
        float weight=aimWeight(actor,id,item,actor.isAiming(),System.nanoTime());
        return adjustment(actor,item,weaponMatrix,standing,aiming,weight);
    }

    static float aimWeight(IsoGameCharacter actor,String id,InventoryItem item,boolean aiming,long now) {
        synchronized(blends){
            var blend=blends.get(actor);
            if(blend==null||blend.item.get()!=item||!blend.modelId.equals(id)){
                blend=new AimBlend(id,item,aiming,now);
                blends.put(actor,blend);
            }
            return blend.advance(aiming,now);
        }
    }

    static GripAdjustment previewRequest(IsoGameCharacter actor,InventoryItem item,Matrix4f weaponMatrix,KahluaTable row) {
        return adjustment(actor,item,weaponMatrix,row,row,0);
    }

    private static GripAdjustment adjustment(IsoGameCharacter actor,InventoryItem item,Matrix4f matrix,KahluaTable standing,KahluaTable aiming,float weight) {
        if(weight==0)aiming=standing;
        if(weight==1)standing=aiming;
        if(standing==null&&aiming==null||matrix==null||!matrix.isFinite()||Math.abs(matrix.determinant())<1e-8f)return null;
        float[] offsets=new float[POSITION_AXES],wrists=new float[POSITION_AXES];
        boolean edited=false;
        for(int i=0;i<POSITION_AXES;i++){
            double offset=value(standing,i),angle=value(standing,POSITION_AXES+i);
            if(standing!=aiming){
                offset+=(value(aiming,i)-offset)*weight;
                angle+=(value(aiming,POSITION_AXES+i)-angle)*weight;
            }
            offsets[i]=(float)(offset*.01);
            wrists[i]=(float)Math.toRadians(angle);
            edited|=offsets[i]!=0||wrists[i]!=0;
        }
        float[] itemOffset=new float[3],curl=new float[2];
        for(int i=0;i<itemOffset.length;i++){
            itemOffset[i]=(float)(blend(standing,aiming,ITEM_AXIS+i,weight)*.01);
            edited|=itemOffset[i]!=0;
        }
        for(int i=0;i<curl.length;i++){
            curl[i]=(float)(blend(standing,aiming,CURL_AXIS+i,weight)*.01);
            edited|=curl[i]!=0;
        }
        return edited?new GripAdjustment(matrix,offsets,wrists,item.getType(),heldSide(actor),itemOffset,curl):null;
    }

    private static double blend(KahluaTable standing,KahluaTable aiming,int axis,float weight) {
        double initial=value(standing,axis);
        return initial+(value(aiming,axis)-initial)*weight;
    }
}
