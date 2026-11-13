if not getActivatedMods():contains("EscapeFromZomboidFirearms") then return end

local Ammo = require "WeaponSystems/Utils/Ammo"
local weaponId = "GFL2.Radiance"

local function configureWeapon()
    local scripts = getScriptManager()
    local weapon = scripts:FindItem(weaponId)
    local donor = scripts:FindItem("EscapeFromZomboidFirearms.MOSIN")
    if not weapon or not donor or not donor:getAmmoType() then return end
    weapon:setAmmoType(donor:getAmmoType())
    weapon:DoParam("AmmoBox", "EscapeFromZomboidFirearms.Box762x54R")
    weapon:DoParam("MaxAmmo", "5")
    Ammo.RegisterItemWithFamily("TW:ammo:762x54R", weaponId)
end

Events.OnGameBoot.Add(configureWeapon)
Events.OnInitGlobalModData.Add(configureWeapon)
