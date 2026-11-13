if not getActivatedMods():contains("EscapeFromZomboidFirearms") then return end

local Ammo = require "WeaponSystems/Utils/Ammo"
local Magazine = require "WeaponSystems/Utils/Magazine"
local weaponId = "GFL2.BittersweetCaramel"

local function configureWeapon()
    local scripts = getScriptManager()
    local weapon = scripts:FindItem(weaponId)
    local donor = scripts:FindItem("EscapeFromZomboidFirearms.G28")
    if not weapon or not donor or not donor:getAmmoType() or not scripts:FindItem("EscapeFromZomboidFirearms.P_617131a4568c120fdd29482d") then return end
    weapon:setAmmoType(donor:getAmmoType())
    weapon:DoParam("AmmoBox", "Base.308Box")
    weapon:DoParam("MaxAmmo", "20")
    weapon:DoParam("MagazineType", "EscapeFromZomboidFirearms.P_617131a4568c120fdd29482d")
    Magazine.RegisterWeaponWithProfile("TW:G28", weaponId)
    Ammo.RegisterItemWithFamily("TW:ammo:308", weaponId)
end

Events.OnGameBoot.Add(configureWeapon)
Events.OnInitGlobalModData.Add(configureWeapon)
