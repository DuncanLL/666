if not getActivatedMods():contains("EscapeFromZomboidFirearms") then return end

local Magazine = require "WeaponSystems/Utils/Magazine"
local Ammo = require "WeaponSystems/Utils/Ammo"
local weaponId = "GFL2.Capitoline"
local efzMagazine = "EscapeFromZomboidFirearms.P_57838f9f2459774a150289a0"

local function configureWeapon()
    local scripts = getScriptManager()
    local weapon = scripts:FindItem(weaponId)
    local donor = scripts:FindItem("EscapeFromZomboidFirearms.ASVAL")
    local magazine = scripts:FindItem(efzMagazine)
    if not weapon or not donor or not magazine or not donor:getAmmoType() then return end
    weapon:setAmmoType(donor:getAmmoType())
    weapon:DoParam("MagazineType", efzMagazine)
    weapon:DoParam("AmmoBox", "EscapeFromZomboidFirearms.AB_61962d879bb3d20b0946d385")
    weapon:DoParam("MaxAmmo", "20")
    Magazine.RegisterWeaponWithProfile("TW:ASVAL", weaponId)
    Ammo.RegisterItemWithFamily("TW:ammo:9x39", weaponId)
end

Events.OnGameBoot.Add(configureWeapon)
Events.OnInitGlobalModData.Add(configureWeapon)
