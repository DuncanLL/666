if not getActivatedMods():contains("EscapeFromZomboidFirearms") then return end

local Magazine = require "WeaponSystems/Utils/Magazine"
local Ammo = require "WeaponSystems/Utils/Ammo"
local weaponId = "GFL2.UnspokenCalling"
local efzMagazine = "EscapeFromZomboidFirearms.P_5926c3b286f774640d189b6b"

local function configureWeapon()
    local scripts = getScriptManager()
    local weapon = scripts:FindItem(weaponId)
    local donor = scripts:FindItem("EscapeFromZomboidFirearms.MP5")
    local magazine = scripts:FindItem(efzMagazine)
    if not weapon or not donor or not magazine or not donor:getAmmoType() then return end
    weapon:setAmmoType(donor:getAmmoType())
    weapon:DoParam("MagazineType", efzMagazine)
    weapon:DoParam("AmmoBox", "Base.Bullets9mmBox")
    weapon:DoParam("MaxAmmo", "30")
    Magazine.RegisterWeaponWithProfile("TW:MP5", weaponId)
    Ammo.RegisterItemWithFamily("TW:ammo:9mm", weaponId)
end

Events.OnGameBoot.Add(configureWeapon)
Events.OnInitGlobalModData.Add(configureWeapon)
