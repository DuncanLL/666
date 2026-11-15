package aimoh.pmxpreview;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.inventory.types.HandWeapon;
import zombie.scripting.ScriptManager;

public final class SignatureWeaponModels {
    public static String magazineModel(HandWeapon weapon, String original) {
        String model = detachableModel(weapon);
        if (weapon.hasModData() && weapon.getModData().rawget("GFL2WeaponSkin") instanceof String skin
                && skin.startsWith(weapon.getFullType() + "Skin")) {
            String variant = model != null && !weapon.isContainsClip() ? skin + "Empty" : skin;
            if (ScriptManager.instance.getModelScript(variant) != null) return variant;
        }
        if (model == null) return original;
        return weapon.isContainsClip() ? model : model + "Empty";
    }

    public static String detachableModel(HandWeapon weapon) {
        return switch (weapon.getFullType()) {
            case "GFL2.Chernobog", "GFL2.AlvaAN94", "GFL2.Scylla", "GFL2.UnspokenCalling", "GFL2.NightRoaming", "GFL2.Skyhowl", "GFL2.Snotra", "GFL2.MaidsRules", "GFL2.SparklingCenterstage", "GFL2.ConfessionCompass", "GFL2.HeavyStrings", "GFL2.Capitoline", "GFL2.SavageBeast", "GFL2.Schlitzohr", "GFL2.Lowenjunges", "GFL2.BansheesWhisper", "GFL2.BittersweetCaramel", "GFL2.GuardianSilverwing", "GFL2.Iaso" -> weapon.getFullType();
            default -> null;
        };
    }

    @Patch(className="zombie.inventory.types.HandWeapon", methodName="getStaticModel")
    public static final class Held {
        @Patch.OnExit
        public static void exit(@Patch.This HandWeapon weapon, @Patch.Return(readOnly=false) String model) {
            model = magazineModel(weapon, model);
        }
    }

    @Patch(className="zombie.inventory.types.HandWeapon", methodName="getWeaponSprite")
    public static final class Sprite {
        @Patch.OnExit
        public static void exit(@Patch.This HandWeapon weapon, @Patch.Return(readOnly=false) String model) {
            model = magazineModel(weapon, model);
        }
    }

    @Patch(className="zombie.inventory.InventoryItem", methodName="getWorldStaticItem")
    public static final class Dropped {
        @Patch.OnExit
        public static void exit(@Patch.This zombie.inventory.InventoryItem item, @Patch.Return(readOnly=false) String model) {
            if (item instanceof HandWeapon weapon) model = magazineModel(weapon, model);
        }
    }

    @Patch(className="zombie.inventory.types.HandWeapon", methodName="setContainsClip")
    public static final class MagazineChanged {
        @Patch.OnEnter
        public static void enter(@Patch.This HandWeapon weapon, @Patch.Argument(0) boolean loaded) {
            if (loaded == weapon.isContainsClip() || detachableModel(weapon)==null) return;
            var owner = weapon.getEquipParent();
            if (owner != null) owner.resetEquippedHandsModels();
        }
    }
}
