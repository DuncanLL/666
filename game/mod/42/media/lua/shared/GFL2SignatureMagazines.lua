local catalog = require "GFL2SignatureWeapon"
local M = {}

function M.forWeapon(weapon)
    if not weapon then return end
    local entry = catalog.byId[weapon:getFullType()]
    if entry and entry.startsWithMagazine then return entry.id .. "Magazine" end
end

local activeMods = getActivatedMods()
if activeMods:contains("SWMG") then
    local Magazine = require "WeaponSystems/Utils/Magazine"
    local usesEFZ = activeMods:contains("EscapeFromZomboidFirearms")
    local Ammo = usesEFZ and require "WeaponSystems/Utils/Ammo"

    function M.register(weaponId, donorId, ammoFamily, ammoBox)
        local magazineId = weaponId .. "Magazine"
        local function configure()
            local scripts = getScriptManager()
            local weapon = scripts:FindItem(weaponId)
            local magazine = scripts:FindItem(magazineId)
            if not weapon or not magazine then return end

            Magazine.RegisterMagazineProfile(weaponId, { magazineId })
            Magazine.RegisterWeaponWithProfile(weaponId, weaponId)
            if not usesEFZ then return end

            local donor = scripts:FindItem(donorId)
            local ammoType = donor and donor:getAmmoType()
            if not ammoType then return end
            weapon:setAmmoType(ammoType)
            magazine:setAmmoType(ammoType)
            weapon:DoParam("AmmoBox", ammoBox)
            Ammo.RegisterItemWithFamily(ammoFamily, { weaponId, magazineId })
        end
        Events.OnGameBoot.Add(configure)
        Events.OnInitGlobalModData.Add(configure)
    end

    local restoreMagazine = Magazine.RestoreMagazineType
    function Magazine.RestoreMagazineType(weapon)
        local dedicated = M.forWeapon(weapon)
        if not dedicated then return restoreMagazine(weapon) end
        local data = weapon:getModData()
        if weapon:getMagazineType() == dedicated and
                (not data.MagazineType or data.MagazineType == dedicated) then return end
        local magazine = instanceItem(dedicated)
        if not magazine then return end
        -- Keep the loaded rounds and chamber state while migrating the saved magazine type.
        weapon:setMagazineType(dedicated)
        weapon:setMaxAmmo(magazine:getMaxAmmo())
        if weapon:isContainsClip() then
            Magazine.SaveMagazineType(weapon, dedicated)
        else
            Magazine.ClearMagazineType(weapon)
            data.MagazineTypeLastIndex = nil
        end
    end
else
    function M.register() end
end

require "TimedActions/ISInsertMagazine"
local isValid = ISInsertMagazine.isValid
function ISInsertMagazine:isValid()
    local dedicated = M.forWeapon(self.gun)
    if dedicated and self.magazine and self.magazine:getFullType() ~= dedicated then return false end
    return isValid(self)
end

return M
