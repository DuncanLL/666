package aimoh.pmxpreview;

import aimoh.motion.WorldPoseRetargeter.GripAdjustment;
import org.joml.Matrix4f;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.inventory.types.HandWeapon;
import zombie.inventory.types.WeaponType;

/** Per-save, per-model offsets keyed by the game's firearm animation family. */
final class GripCalibration {
    private static final String STORE="AimohR116GripCalibration";
    private static final int COUNT=6, LIMIT=12;

    private static HandWeapon weapon(IsoGameCharacter actor) {
        return actor instanceof IsoPlayer player && player.isLocalPlayer()
            && actor.getPrimaryHandItem() instanceof HandWeapon item && item.isRanged()
            && item.getFullType().startsWith("Base.") ? item : null;
    }

    static String family(IsoGameCharacter actor) {
        HandWeapon item=weapon(actor);
        return item==null?"":WeaponType.getWeaponType(item).getType();
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

    private static double value(KahluaTable row,int axis) {
        Object raw=row==null?null:row.rawget(axis+1);
        return raw instanceof Number n&&Double.isFinite(n.doubleValue())&&Math.abs(n.doubleValue())<=LIMIT?n.doubleValue():0;
    }

    static KahluaTable sliders(IsoGameCharacter actor,String id,String family) {
        KahluaTable row=table(model(actor,id,false),family,false);
        KahluaTable values=J2SEPlatform.getInstance().newTable();
        for(int i=0;i<COUNT;i++)values.rawset(i+1,Double.valueOf(value(row,i)));
        return values;
    }

    static boolean set(IsoGameCharacter actor,String id,String family,int axis,double offset) {
        if(family==null||family.isEmpty()||!family.equals(family(actor)))return false;
        if(axis==-1){
            KahluaTable saved=model(actor,id,false);
            if(saved!=null)saved.rawset(family,null);
            return true;
        }
        if(axis<0||axis>=COUNT||!Double.isFinite(offset)||Math.abs(offset)>LIMIT||offset!=Math.rint(offset))return false;
        table(model(actor,id,true),family,true).rawset(axis+1,Double.valueOf(offset));
        return true;
    }

    static GripAdjustment request(IsoGameCharacter actor,String id,Matrix4f weaponMatrix) {
        if(weaponMatrix==null||!weaponMatrix.isFinite()||Math.abs(weaponMatrix.determinant())<1e-8f
            ||!actor.isAiming()||actor.getVariableBoolean("isLoading")||actor.getVariableBoolean("isRacking"))return null;
        String reload=actor.getVariableString("WeaponReloadType");
        if(reload!=null&&!reload.isEmpty())return null;
        HandWeapon item=weapon(actor);
        if(item==null)return null;
        KahluaTable row=table(model(actor,id,false),WeaponType.getWeaponType(item).getType(),false);
        if(row==null)return null;
        float[] offsets=new float[COUNT];
        boolean edited=false;
        for(int i=0;i<COUNT;i++){
            offsets[i]=(float)(value(row,i)*.01);
            edited|=offsets[i]!=0;
        }
        return edited?new GripAdjustment(weaponMatrix,offsets,item.getType()):null;
    }
}
