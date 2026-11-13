if not getActivatedMods():contains("EscapeFromZomboidFirearms") then return end

local Magazine = require "WeaponSystems/Utils/Magazine"
local Ammo = require "WeaponSystems/Utils/Ammo"
local weaponId = "GFL2.AlvaAN94"
local efzMagazine = "EscapeFromZomboidFirearms.P_55d480c04bdc2d1d4e8b456a"

local function configureWeapon()
    local scripts = getScriptManager()
    local weapon = scripts:FindItem(weaponId)
    local donor = scripts:FindItem("EscapeFromZomboidFirearms.AK74M")
    local magazine = scripts:FindItem(efzMagazine)
    if not weapon or not donor or not magazine or not donor:getAmmoType() then return end
    weapon:setAmmoType(donor:getAmmoType())
    weapon:DoParam("MagazineType", efzMagazine)
    weapon:DoParam("AmmoBox", "EscapeFromZomboidFirearms.Box545x39")
    Magazine.RegisterMagazineProfile("GFL2:AlvaAN94", {efzMagazine})
    Magazine.RegisterWeaponWithProfile("GFL2:AlvaAN94", weaponId)
    Ammo.RegisterItemWithFamily("TW:ammo:545", weaponId)
end

Events.OnGameBoot.Add(configureWeapon)
Events.OnInitGlobalModData.Add(configureWeapon)
