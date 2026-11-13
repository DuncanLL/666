if not getActivatedMods():contains("EscapeFromZomboidFirearms") then return end

local Magazine = require "WeaponSystems/Utils/Magazine"
local Ammo = require "WeaponSystems/Utils/Ammo"
local weaponId = "GFL2.NightRoaming"
local efzMagazine = "EscapeFromZomboidFirearms.P_673cbdfad0453ba50c0f76d6"

local function configureWeapon()
    local scripts = getScriptManager()
    local weapon = scripts:FindItem(weaponId)
    local donor = scripts:FindItem("EscapeFromZomboidFirearms.TRGM10")
    local magazine = scripts:FindItem(efzMagazine)
    if not weapon or not donor or not magazine or not donor:getAmmoType() then return end
    weapon:setAmmoType(donor:getAmmoType())
    weapon:DoParam("MagazineType", efzMagazine)
    weapon:DoParam("AmmoBox", "EscapeFromZomboidFirearms.AB_5fc382c1016cce60e8341b20")
    weapon:DoParam("MaxAmmo", "8")
    Magazine.RegisterWeaponWithProfile("TW:TRGM10", weaponId)
    Ammo.RegisterItemWithFamily("TW:ammo:338", weaponId)
end

Events.OnGameBoot.Add(configureWeapon)
Events.OnInitGlobalModData.Add(configureWeapon)
