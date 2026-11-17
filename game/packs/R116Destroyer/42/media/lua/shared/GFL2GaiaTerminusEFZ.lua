if not getActivatedMods():contains("EscapeFromZomboidFirearms") then return end

local Ammo = require "WeaponSystems/Utils/Ammo"
local weaponId = "GFL2.GaiaTerminus"

local function configureWeapon()
    local scripts = getScriptManager()
    local weapon = scripts:FindItem(weaponId)
    local donor = scripts:FindItem("EscapeFromZomboidFirearms.MP155")
    if not weapon or not donor or not donor:getAmmoType() then return end
    weapon:setAmmoType(donor:getAmmoType())
    weapon:DoParam("AmmoBox", "Base.ShotgunShellsBox")
    weapon:DoParam("MaxAmmo", "6")
    Ammo.RegisterItemWithFamily("TW:ammo:12ga", weaponId)
end

Events.OnGameBoot.Add(configureWeapon)
Events.OnInitGlobalModData.Add(configureWeapon)
