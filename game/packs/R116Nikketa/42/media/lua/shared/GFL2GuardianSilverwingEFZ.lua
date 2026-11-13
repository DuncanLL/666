if not getActivatedMods():contains("EscapeFromZomboidFirearms") then return end

local Ammo = require "WeaponSystems/Utils/Ammo"
local Magazine = require "WeaponSystems/Utils/Magazine"
local weaponId = "GFL2.GuardianSilverwing"

local function configureWeapon()
    local scripts = getScriptManager()
    local weapon = scripts:FindItem(weaponId)
    local donor = scripts:FindItem("EscapeFromZomboidFirearms.VSS")
    if not weapon or not donor or not donor:getAmmoType() or not scripts:FindItem("EscapeFromZomboidFirearms.P_57838f0b2459774a256959b2") then return end
    weapon:setAmmoType(donor:getAmmoType())
    weapon:DoParam("AmmoBox", "EscapeFromZomboidFirearms.AB_61962d879bb3d20b0946d385")
    weapon:DoParam("MaxAmmo", "10")
    weapon:DoParam("MagazineType", "EscapeFromZomboidFirearms.P_57838f0b2459774a256959b2")
    Magazine.RegisterWeaponWithProfile("TW:VSS", weaponId)
    Ammo.RegisterItemWithFamily("TW:ammo:9x39", weaponId)
end

Events.OnGameBoot.Add(configureWeapon)
Events.OnInitGlobalModData.Add(configureWeapon)
