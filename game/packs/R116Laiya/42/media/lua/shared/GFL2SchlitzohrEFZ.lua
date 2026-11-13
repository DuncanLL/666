if not getActivatedMods():contains("EscapeFromZomboidFirearms") then return end

local Ammo = require "WeaponSystems/Utils/Ammo"
local Magazine = require "WeaponSystems/Utils/Magazine"
local weaponId = "GFL2.Schlitzohr"

local function configureWeapon()
    local scripts = getScriptManager()
    local weapon = scripts:FindItem(weaponId)
    local donor = scripts:FindItem("EscapeFromZomboidFirearms.UMP45")
    if not weapon or not donor or not donor:getAmmoType() or not scripts:FindItem("EscapeFromZomboidFirearms.P_5fc3e466187fea44d52eda90") then return end
    weapon:setAmmoType(donor:getAmmoType())
    weapon:DoParam("AmmoBox", "Base.Bullets45Box")
    weapon:DoParam("MaxAmmo", "25")
    weapon:DoParam("MagazineType", "EscapeFromZomboidFirearms.P_5fc3e466187fea44d52eda90")
    Magazine.RegisterWeaponWithProfile("TW:UMP45", weaponId)
    Ammo.RegisterItemWithFamily("TW:ammo:45", weaponId)
end

Events.OnGameBoot.Add(configureWeapon)
Events.OnInitGlobalModData.Add(configureWeapon)
