package aimoh.pmxpreview;

import aimoh.motion.WorldPoseRetargeter.GripAdjustment;
import org.joml.Matrix4f;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.HandWeapon;
import zombie.inventory.types.WeaponType;

/** Per-save, per-model calibration keyed by the held item's full type. */
final class GripCalibration {
    private static final String STORE="AimohR116GripCalibration";
    private static final int POSITION_AXES=6, COUNT=12;

    private static int limit(int axis){return axis<POSITION_AXES?12:90;}

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

    private static KahluaTable model(IsoGameCharacter actor,String id,boolean create) {
        return table(table(actor.getModData(),STORE,create),id,create);
    }

    private static KahluaTable legacy(KahluaTable model,InventoryItem item) {
        if(!(item instanceof HandWeapon weapon)||!weapon.isRanged())return null;
        String family=(weapon.isTwoHandWeapon()?WeaponType.FIREARM:WeaponType.HANDGUN).getType();
        return table(model,family,false);
    }

    private static KahluaTable row(KahluaTable model,InventoryItem item) {
        if(model==null)return null;
        Object saved=model.rawget(item.getFullType());
        if(saved instanceof KahluaTable row)return row;
        return saved==null?legacy(model,item):null;
    }

    private static double value(KahluaTable row,int axis) {
        Object raw=row==null?null:row.rawget(axis+1);
        return raw instanceof Number n&&Double.isFinite(n.doubleValue())&&Math.abs(n.doubleValue())<=limit(axis)?n.doubleValue():0;
    }

    static KahluaTable sliders(IsoGameCharacter actor,String id,String key) {
        InventoryItem item=heldItem(actor);
        KahluaTable row=item!=null&&key.equals(item.getFullType())?row(model(actor,id,false),item):null;
        KahluaTable values=J2SEPlatform.getInstance().newTable();
        for(int i=0;i<COUNT;i++)values.rawset(i+1,Double.valueOf(value(row,i)));
        return values;
    }

    static boolean set(IsoGameCharacter actor,String id,String key,int axis,double offset) {
        InventoryItem item=heldItem(actor);
        if(item==null||!item.getFullType().equals(key))return false;
        if(axis!=-1&&(axis<0||axis>=COUNT||!Double.isFinite(offset)||Math.abs(offset)>limit(axis)||offset!=Math.rint(offset)))return false;
        KahluaTable saved=model(actor,id,false);
        KahluaTable previous=row(saved,item);
        if(axis!=-1&&value(previous,axis)==offset)return true;
        KahluaTable next=J2SEPlatform.getInstance().newTable();
        boolean edited=false;
        if(axis!=-1)for(int i=0;i<COUNT;i++){
            double v=i==axis?offset:value(previous,i);
            if(v!=0){next.rawset(i+1,Double.valueOf(v));edited=true;}
        }
        if(edited)model(actor,id,true).rawset(key,next);
        else if(saved!=null)saved.rawset(key,legacy(saved,item)!=null?Boolean.FALSE:null);
        return true;
    }

    static GripAdjustment request(IsoGameCharacter actor,String id,Matrix4f weaponMatrix) {
        if(weaponMatrix==null)return null;
        InventoryItem item=heldItem(actor);
        if(item==null)return null;
        KahluaTable row=row(model(actor,id,false),item);
        if(row==null)return null;
        if(!weaponMatrix.isFinite()||Math.abs(weaponMatrix.determinant())<1e-8f
            ||actor.getVariableBoolean("isLoading")||actor.getVariableBoolean("isRacking"))return null;
        String reload=actor.getVariableString("WeaponReloadType");
        if(reload!=null&&!reload.isEmpty())return null;
        float[] offsets=new float[POSITION_AXES],wrists=new float[POSITION_AXES];
        boolean edited=false;
        for(int i=0;i<POSITION_AXES;i++){
            offsets[i]=(float)(value(row,i)*.01);
            wrists[i]=(float)Math.toRadians(value(row,POSITION_AXES+i));
            edited|=offsets[i]!=0||wrists[i]!=0;
        }
        return edited?new GripAdjustment(weaponMatrix,offsets,wrists,item.getType(),heldSide(actor)):null;
    }
}
