local key = "AimohPMXPreview"
local window = require "AimohPMXWindow"
local economy = require "AimohPMXRoomEconomy"
local multiplayer = require "GFL2MultiplayerClient"
local started = false
local awaiting = {}
local expressionEdits = {}

local function problem(player, message)
    if message and message ~= "" then
        print("[GFL2Exilium] " .. message)
        if player then player:Say(getText("ContextMenu_AimohPMX_Error")) end
        return true
    end
    return false
end

local function publish(player, saved)
    if isClient() then multiplayer.publish(player,saved) end
end

local function voiceChanged(player)
    if AimohPMXVoiceEngine then AimohPMXVoiceEngine.modelChanged(player) end
end

local function remember(player,quiet)
    expressionEdits[player]=nil
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
    economy.saveLocal(player)
    publish(player, saved)
    if not quiet then voiceChanged(player) end
end

multiplayer.onRestored=remember

local function flushExpression(player)
    if not expressionEdits[player] then return end
    expressionEdits[player]=nil
    if AimohPMXPreview and AimohPMXPreview.isReady(player) then remember(player,true) end
end

local function enable(player, index)
    flushExpression(player)
    local previous=player:getModData()[key]
    local profiles=previous and previous.profiles or {}
    if previous and previous.model and previous.settings then profiles[previous.model]=previous.settings end
    if problem(player, AimohPMXPreview.enableWithSettings(player,index,profiles[AimohPMXPreview.modelIdAt(index)] or "")) then return false end
    local saved=player:getModData()[key] or {};saved.profiles=profiles;player:getModData()[key]=saved
    awaiting[player]=true;return true
end

Events.OnTick.Add(function()
    for player,deadline in pairs(expressionEdits) do
        if getTimestampMs()>=deadline then flushExpression(player) end
    end
    for player in pairs(awaiting) do
        if AimohPMXPreview.hasModelFailure(player) then
            awaiting[player]=nil
            problem(player,AimohPMXPreview.status());voiceChanged(player)
        elseif AimohPMXPreview.isReady(player) then
            awaiting[player]=nil;remember(player)
        end
    end
end)

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


local function setChestPhysics(player,enabled)
    if problem(player,AimohPMXPreview.setChestPhysicsEnabled(player,enabled))then return false end
    remember(player,true)
    return true
end

local function disable(player)
    flushExpression(player)
    awaiting[player]=nil
    AimohPMXPreview.disable(player)
    local saved=player:getModData()[key]
    player:getModData()[key] = saved and {profiles=saved.profiles} or nil
    economy.saveLocal(player)
    publish(player, nil)
    voiceChanged(player)
end

local function refresh(player)
    if problem(player,AimohPMXPreview.refreshLibrary()) then return false end
    if isClient() then multiplayer.resync() end
    return true
end

local function restore(player)
    if not player or not AimohPMXPreview or isClient() then return end
    economy.restoreLocal(player)
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
    window.open(player,{enable=enable,choice=choice,preset=preset,reload=reload,disable=disable,material=material,reset=reset,refresh=refresh,physicsReset=resetPhysics,chestPhysics=setChestPhysics,
        expressionRemember=function(p)remember(p,true)end,
        expressionEdited=function(p)expressionEdits[p]=getTimestampMs()+250 end})
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
    awaiting={}
    expressionEdits={}
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
Events.OnSave.Add(function()
    if started then
        for i = 0, 3 do
            local player = getSpecificPlayer(i)
            if player and not player:isDead() then
                flushExpression(player)
                economy.saveLocal(player)
            end
        end
    end
end)
Events.OnPlayerDeath.Add(function(player)
    flushExpression(player)
    economy.saveLocal(player)
    awaiting[player]=nil
    window.closeFor(player)
    if AimohPMXPreview then
        AimohPMXPreview.disable(player)
    end
end)
if Events.OnMainMenuEnter then
    Events.OnMainMenuEnter.Add(function()
        started = false
        awaiting={}
        expressionEdits={}
        window.closeAll()
        if AimohPMXPreview then AimohPMXPreview.clear() end
    end)
end
Events.OnDisconnect.Add(function()
    awaiting={}
    expressionEdits={}
    window.closeAll()
    started = false
    if AimohPMXPreview then AimohPMXPreview.clear() end
end)
