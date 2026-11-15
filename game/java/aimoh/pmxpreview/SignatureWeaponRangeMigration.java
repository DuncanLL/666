package aimoh.pmxpreview;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.inventory.types.HandWeapon;
import zombie.inventory.types.WeaponPart;

/** Update saved signature firearm stats while retaining weapon part modifiers. */
@Patch(className = "zombie.inventory.types.HandWeapon", methodName = "load")
public final class SignatureWeaponRangeMigration {
    @Patch.OnExit
    public static void exit(@Patch.This HandWeapon weapon) {
        migrate(weapon);
    }

    public static void migrate(HandWeapon weapon) {
        if (!weapon.isRanged() || !weapon.getFullType().startsWith("GFL2.") || weapon.getScriptItem() == null) {
            return;
        }

        float scriptRange = weapon.getScriptItem().getMaxRange();
        float oldBase = scriptRange / 2.0f;
        float attachmentRange = 0.0f;
        for (WeaponPart part : weapon.getAllWeaponParts()) {
            attachmentRange += part.getMaxRange();
        }

        if (Math.abs(weapon.getMaxRange() - attachmentRange - oldBase) < 0.01f) {
            weapon.setMaxRange(scriptRange + attachmentRange);
        }

        // Only values that matched the 0.7.4 base after subtracting modifiers migrate.
        switch (weapon.getFullType()) {
            case "GFL2.Schlitzohr" -> migrateDamage(weapon, 0.8f, 1.2f);
            case "GFL2.Lowenjunges", "GFL2.UnspokenCalling" -> migrateDamage(weapon, 0.6f, 1.1f);
            case "GFL2.AlvaAN94", "GFL2.Chernobog" -> migrateDamage(weapon, 1.0f, 1.6f);
            case "GFL2.Radiance" -> {
                migrateDamage(weapon, 1.4f, 2.2f);
                int partHit = 0;
                for (WeaponPart part : weapon.getAllWeaponParts()) {
                    partHit += part.getHitChance();
                }
                if (weapon.getHitChance() - partHit == 55) {
                    weapon.setHitChance(weapon.getScriptItem().hitChance + partHit);
                }
            }
            case "GFL2.BittersweetCaramel" -> migrateDamage(weapon, 1.2f, 2.0f);
            default -> { }
        }
    }

    private static void migrateDamage(HandWeapon weapon, float oldMin, float oldMax) {
        float partDamage = 0.0f;
        for (WeaponPart part : weapon.getAllWeaponParts()) {
            partDamage += part.getDamage();
        }

        var script = weapon.getScriptItem();
        if (Math.abs(weapon.getMinDamage() - partDamage - oldMin) < 0.01f) {
            weapon.setMinDamage(script.getMinDamage() + partDamage);
        }
        if (Math.abs(weapon.getMaxDamage() - partDamage - oldMax) < 0.01f) {
            weapon.setMaxDamage(script.getMaxDamage() + partDamage);
        }
    }
}
