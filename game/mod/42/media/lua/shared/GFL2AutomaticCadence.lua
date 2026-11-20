if not getActivatedMods():contains("SWMG") then return end

local RateOfFire = require "WeaponSystems/Utils/RateOfFire"
local Stats = require "WeaponSystems/Utils/StatsFactory"

local automaticRPM = {
    ["GFL2.AlvaAN94"] = 650,
    ["GFL2.BansheesWhisper"] = 1100,
    ["GFL2.Capitoline"] = 700,
    ["GFL2.Chernobog"] = 700,
    ["GFL2.ConfessionCompass"] = 800, -- M4A1 reference for the fictional AR-15 variant.
    ["GFL2.Daydream"] = 900,
    ["GFL2.HeavyStrings"] = 650,
    ["GFL2.Lowenjunges"] = 650,
    ["GFL2.MaidsRules"] = 750,
    ["GFL2.SavageBeast"] = 850,
    ["GFL2.Schlitzohr"] = 600,
    ["GFL2.Scylla"] = 850,
    ["GFL2.Skyhowl"] = 800,
    ["GFL2.Snotra"] = 750,
    ["GFL2.SparklingCenterstage"] = 700,
    ["GFL2.UnspokenCalling"] = 850,
}

for weaponType, rpm in pairs(automaticRPM) do
    RateOfFire.RegisterWeapon(weaponType, { rpm = rpm })
end

-- Match EFZ's automatic path so the vanilla recoil delay cannot add a tick to the RPM gate.
Stats.RegisterModifierLayer("GFL2AutomaticCadence", function(weapon)
    if weapon:getFireMode() == "RealAuto" then
        return { Stats.Set("RecoilDelay", 0) }
    end
end, { RecoilDelay = true }, function(weapon)
    return automaticRPM[weapon:getFullType()] ~= nil
end)
