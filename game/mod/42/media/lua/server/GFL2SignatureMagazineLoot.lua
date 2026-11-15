require "Items/ProceduralDistributions"
local catalog = require "GFL2SignatureWeapon"

Events.OnPreDistributionMerge.Add(function()
    local items = ProceduralDistributions.list.GunStoreMagsAmmo.items
    local stocked = {}
    for i = 1, #items, 2 do stocked[items[i]] = true end

    for weaponId, weapon in pairs(catalog.byId) do
        local magazineId = weaponId .. "Magazine"
        if weapon.startsWithMagazine and not stocked[magazineId] and
                getScriptManager():FindItem(magazineId) then
            items[#items + 1] = magazineId
            items[#items + 1] = 8
        end
    end
end)
