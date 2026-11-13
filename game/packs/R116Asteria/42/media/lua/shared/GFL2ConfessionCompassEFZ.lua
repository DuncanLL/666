if not getActivatedMods():contains("EscapeFromZomboidFirearms") then return end

local Magazine = require "WeaponSystems/Utils/Magazine"
local Ammo = require "WeaponSystems/Utils/Ammo"
local weaponId = "GFL2.ConfessionCompass"
local efzMagazine = "EscapeFromZomboidFirearms.P_55d4887d4bdc2d962f8b4570"

local function configureWeapon()
    local scripts = getScriptManager()
    local weapon = scripts:FindItem(weaponId)
    local donor = scripts:FindItem("EscapeFromZomboidFirearms.M4A1")
    local magazine = scripts:FindItem(efzMagazine)
    if not weapon or not donor or not magazine or not donor:getAmmoType() then return end
    weapon:setAmmoType(donor:getAmmoType())
    weapon:DoParam("MagazineType", efzMagazine)
    weapon:DoParam("AmmoBox", "Base.556Box")
    weapon:DoParam("MaxAmmo", "30")
    Magazine.RegisterWeaponWithProfile("TW:M4A1", weaponId)
    Ammo.RegisterItemWithFamily("TW:ammo:556", weaponId)
end

Events.OnGameBoot.Add(configureWeapon)
Events.OnInitGlobalModData.Add(configureWeapon)
