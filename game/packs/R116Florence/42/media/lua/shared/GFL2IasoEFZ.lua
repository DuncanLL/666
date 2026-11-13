if not getActivatedMods():contains("EscapeFromZomboidFirearms") then return end

local Ammo = require "WeaponSystems/Utils/Ammo"
local Magazine = require "WeaponSystems/Utils/Magazine"
local weaponId = "GFL2.Iaso"

local function configureWeapon()
    local scripts = getScriptManager()
    local weapon = scripts:FindItem(weaponId)
    local donor = scripts:FindItem("EscapeFromZomboidFirearms.M9A3")
    if not weapon or not donor or not donor:getAmmoType() or not scripts:FindItem("EscapeFromZomboidFirearms.P_5cadc2e0ae9215051e1c21e7") then return end
    weapon:setAmmoType(donor:getAmmoType())
    weapon:DoParam("AmmoBox", "Base.Bullets9mmBox")
    weapon:DoParam("MaxAmmo", "17")
    weapon:DoParam("MagazineType", "EscapeFromZomboidFirearms.P_5cadc2e0ae9215051e1c21e7")
    Magazine.RegisterWeaponWithProfile("TW:M9A3", weaponId)
    Ammo.RegisterItemWithFamily("TW:ammo:9mm", weaponId)
end

Events.OnGameBoot.Add(configureWeapon)
Events.OnInitGlobalModData.Add(configureWeapon)
