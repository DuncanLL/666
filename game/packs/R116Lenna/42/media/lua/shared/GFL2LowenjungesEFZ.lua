if not getActivatedMods():contains("EscapeFromZomboidFirearms") then return end

local Ammo = require "WeaponSystems/Utils/Ammo"
local Magazine = require "WeaponSystems/Utils/Magazine"
local weaponId = "GFL2.Lowenjunges"

local function configureWeapon()
    local scripts = getScriptManager()
    local weapon = scripts:FindItem(weaponId)
    local donor = scripts:FindItem("EscapeFromZomboidFirearms.MP5")
    if not weapon or not donor or not donor:getAmmoType() or not scripts:FindItem("EscapeFromZomboidFirearms.P_5926c3b286f774640d189b6b") then return end
    weapon:setAmmoType(donor:getAmmoType())
    weapon:DoParam("AmmoBox", "Base.Bullets9mmBox")
    weapon:DoParam("MaxAmmo", "30")
    weapon:DoParam("MagazineType", "EscapeFromZomboidFirearms.P_5926c3b286f774640d189b6b")
    Magazine.RegisterWeaponWithProfile("TW:MP5", weaponId)
    Ammo.RegisterItemWithFamily("TW:ammo:9mm", weaponId)
end

Events.OnGameBoot.Add(configureWeapon)
Events.OnInitGlobalModData.Add(configureWeapon)
