local options = {
    ["GFL2.AlvaAN94"] = { { id="4334325", model="GFL2.AlvaAN94Skin4334325", detached=true } },
    ["GFL2.BansheesWhisper"] = { { id="4332085", model="GFL2.BansheesWhisperSkin4332085", detached=true } },
    ["GFL2.BittersweetCaramel"] = { { id="4332115", model="GFL2.BittersweetCaramelSkin4332115", detached=true } },
    ["GFL2.Capitoline"] = { { id="4334545", model="GFL2.CapitolineSkin4334545", detached=true } },
    ["GFL2.CauseAndEffect"] = { { id="4337415", model="GFL2.CauseAndEffectSkin4337415", detached=false } },
    ["GFL2.Chernobog"] = { { id="4334615", model="GFL2.ChernobogSkin4334615", detached=true } },
    ["GFL2.ConfessionCompass"] = { { id="4334625", model="GFL2.ConfessionCompassSkin4334625", detached=true } },
    ["GFL2.Daydream"] = { { id="4334065", model="GFL2.DaydreamSkin4334065", detached=false } },
    ["GFL2.GaiaTerminus"] = { { id="4336675", model="GFL2.GaiaTerminusSkin4336675", detached=false } },
    ["GFL2.GuardianSilverwing"] = { { id="4333225", model="GFL2.GuardianSilverwingSkin4333225", detached=true } },
    ["GFL2.HeavyStrings"] = { { id="4334075", model="GFL2.HeavyStringsSkin4334075", detached=true } },
    ["GFL2.Iaso"] = { { id="4331295", model="GFL2.IasoSkin4331295", detached=true } },
    ["GFL2.Lowenjunges"] = { { id="4332245", model="GFL2.LowenjungesSkin4332245", detached=true } },
    ["GFL2.MaidsRules"] = { { id="4334215", model="GFL2.MaidsRulesSkin4334215", detached=true } },
    ["GFL2.NightRoaming"] = { { id="4333475", model="GFL2.NightRoamingSkin4333475", detached=true } },
    ["GFL2.Radiance"] = { { id="4333145", model="GFL2.RadianceSkin4333145", detached=false } },
    ["GFL2.SavageBeast"] = { { id="4334605", model="GFL2.SavageBeastSkin4334605", detached=true } },
    ["GFL2.Schlitzohr"] = { { id="4332235", model="GFL2.SchlitzohrSkin4332235", detached=true } },
    ["GFL2.Scylla"] = { { id="4334055", model="GFL2.ScyllaSkin4334055", detached=true } },
    ["GFL2.Skyhowl"] = { { id="4332565", model="GFL2.SkyhowlSkin4332565", detached=true } },
    ["GFL2.Snotra"] = { { id="4332645", model="GFL2.SnotraSkin4332645", detached=true } },
    ["GFL2.SparklingCenterstage"] = { { id="4334195", model="GFL2.SparklingCenterstageSkin4334195", detached=true } },
    ["GFL2.TidalNocturne"] = { { id="4333455", model="GFL2.TidalNocturneSkin4333455", detached=false } },
    ["GFL2.Trailblazer"] = { { id="4336185", model="GFL2.TrailblazerSkin4336185", detached=false } },
    ["GFL2.TwilightRose"] = { { id="4337535", model="GFL2.TwilightRoseSkin4337535", detached=false } },
    ["GFL2.UnspokenCalling"] = { { id="4334095", model="GFL2.UnspokenCallingSkin4334095", detached=true } },
}
local M = {}

local function loaded(skin)
    local scripts=getScriptManager()
    return scripts:getModelScript(skin.model) and
        (not skin.detached or scripts:getModelScript(skin.model.."Empty"))
end

function M.available(weaponId)
    local result={}
    for _,skin in ipairs(options[weaponId] or {}) do
        if loaded(skin) then result[#result+1]=skin end
    end
    return result
end

function M.model(weaponId,id)
    if not id or id=="" then return end
    for _,skin in ipairs(options[weaponId] or {}) do
        if skin.id==id and loaded(skin) then return skin.model end
    end
end

return M
