local key = "AimohPMXPreview"
local window = require "AimohPMXWindow"
local started = false

local function problem(player, message)
    if message and message ~= "" then
        print("[AimohPMXPreview] " .. message)
        if player then player:Say(getText("ContextMenu_AimohPMX_Error")) end
        return true
    end
    return false
end

local function publish(player, saved)
    local multiplayer=rawget(_G,"AimohPMXMPClient")
    if multiplayer then multiplayer.publish(player,saved) end
end

local function voiceChanged(player)
    if AimohPMXVoiceEngine then AimohPMXVoiceEngine.modelChanged(player) end
end

local function remember(player,quiet)
    local previous=player:getModData()[key]
    local profiles=previous and previous.profiles or {}
    local saved = {
        model = AimohPMXPreview.modelId(player),
        appearance = AimohPMXPreview.appearanceJson(player),
        settings = AimohPMXPreview.settingsJson(player),
        profiles = profiles,
    }
    profiles[saved.model]=saved.settings
    player:getModData()[key] = saved
    publish(player, saved)
    if not quiet then voiceChanged(player) end
end

local function enable(player, index)
    local previous=player:getModData()[key]
    local profiles=previous and previous.profiles or {}
    if previous and previous.model and previous.settings then profiles[previous.model]=previous.settings end
    if problem(player, AimohPMXPreview.enableWithSettings(player,index,profiles[AimohPMXPreview.modelIdAt(index)] or "")) then return false end
    local saved=player:getModData()[key] or {};saved.profiles=profiles;player:getModData()[key]=saved
    remember(player);return true
end

local function choice(player, group, option)
    if problem(player, AimohPMXPreview.setChoice(player, group, option)) then return false end
    remember(player);return true
end

local function preset(player, index)
    if problem(player, AimohPMXPreview.setPreset(player, index)) then return false end
    remember(player);return true
end

local function reload(player)
    if problem(player, AimohPMXPreview.reloadAppearance(player)) then return false end
    remember(player);return true
end

local function material(player,index,visible)
    if problem(player,AimohPMXPreview.setMaterialVisible(player,index,visible)) then return false end
    remember(player);return true
end
local function reset(player)
    if problem(player,AimohPMXPreview.resetAppearance(player)) then return false end
    remember(player);return true
end

local function resetPhysics(player)
    return not problem(player,AimohPMXPreview.resetModelPhysics(player))
end

local function chestPhysics(player,index,value)
    if problem(player,AimohPMXPreview.setChestPhysicsSlider(player,index,value))then return false end
    remember(player,true);return true
end
local function chestDefaults(player)
    if problem(player,AimohPMXPreview.resetChestPhysicsSettings(player))then return false end
    remember(player,true);return true
end

local function disable(player)
    AimohPMXPreview.disable(player)
    local saved=player:getModData()[key]
    player:getModData()[key] = saved and {profiles=saved.profiles} or nil
    publish(player, nil)
    voiceChanged(player)
end

local function refresh(player)
    if problem(player,AimohPMXPreview.refreshLibrary()) then return false end
    local multiplayer=rawget(_G,"AimohPMXMPClient")
    if multiplayer then multiplayer.resync() end
    return true
end

local function restore(player)
    if not player or not AimohPMXPreview then return end
    local saved = player:getModData()[key]
    if type(saved) == "table" and type(saved.model) == "string" then
        local message
        if saved.settings then message = AimohPMXPreview.queueSettings(player, saved.model, saved.settings)
        else message = AimohPMXPreview.queueRestore(player, saved.model, saved.appearance or "") end
        if not problem(nil, message) then publish(player, saved) end
    else
        publish(player, nil)
    end
end

local function open(player)
    window.open(player,{enable=enable,choice=choice,preset=preset,reload=reload,disable=disable,material=material,reset=reset,refresh=refresh,physicsReset=resetPhysics,chestPhysics=chestPhysics,chestDefaults=chestDefaults})
end

local function menu(playerIndex, context, worldObjects, test)
    if test then return ISWorldObjectContextMenu.setTest() end
    local player = getSpecificPlayer(playerIndex)
    if not player then return end
    if not AimohPMXPreview then
        local unavailable = context:addOption(getText("ContextMenu_AimohPMX_JavaUnavailable"))
        unavailable.notAvailable = true
        return
    end
    context:addOption(getText("ContextMenu_AimohPMX_Menu"),player,open)
end

Events.OnFillWorldObjectContextMenu.Add(menu)
Events.OnGameStart.Add(function()
    window.closeAll()
    started = true
    if not AimohPMXPreview then return end
    AimohPMXPreview.clear()
    for i = 0, 3 do restore(getSpecificPlayer(i)) end
end)
Events.OnCreatePlayer.Add(function(index, player)
    if started then
        restore(player)
    end
end)
Events.OnPlayerDeath.Add(function(player)
    window.closeFor(player)
    if AimohPMXPreview then
        AimohPMXPreview.disable(player)
        if not isClient() or player:isLocalPlayer() then publish(player, nil) end
    end
end)
if Events.OnMainMenuEnter then
    Events.OnMainMenuEnter.Add(function()
        started = false
        window.closeAll()
        if AimohPMXPreview then AimohPMXPreview.clear() end
    end)
end
Events.OnDisconnect.Add(function()
    window.closeAll()
    started = false
    if AimohPMXPreview then AimohPMXPreview.clear() end
end)
