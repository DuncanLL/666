local key = "GFL2CharacterRuntime"
local window = require "GFL2RoomWindow"
local economy = require "GFL2RoomEconomy"
local multiplayer = require "GFL2MultiplayerClient"
local started = false
local awaiting = {}
local expressionEdits = {}
local voiceSaveEdits = {}

local function problem(player, message)
    if message and message ~= "" then
        print("[GFL2Exilium] " .. message)
        if player then player:Say(getText("ContextMenu_GFL2Character_Error")) end
        return true
    end
    return false
end

local function publish(player, saved)
    if isClient() then multiplayer.publish(player,saved) end
end

local function voiceChanged(player)
    if GFL2VoiceEngine then GFL2VoiceEngine.modelChanged(player) end
end

local function remember(player,quiet)
    expressionEdits[player]=nil
    voiceSaveEdits[player]=nil
    local previous=player:getModData()[key]
    local profiles=previous and previous.profiles or {}
    local saved = {
        model = GFL2CharacterRuntime.modelId(player),
        appearance = GFL2CharacterRuntime.appearanceJson(player),
        settings = GFL2CharacterRuntime.settingsJson(player),
        profiles = profiles,
        voiceVolume = previous and previous.voiceVolume,
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
    if GFL2CharacterRuntime and GFL2CharacterRuntime.isReady(player) then remember(player,true) end
end

local function enable(player, index)
    flushExpression(player)
    local previous=player:getModData()[key]
    local profiles=previous and previous.profiles or {}
    if previous and previous.model and previous.settings then profiles[previous.model]=previous.settings end
    if problem(player, GFL2CharacterRuntime.enableWithSettings(player,index,profiles[GFL2CharacterRuntime.modelIdAt(index)] or "")) then return false end
    local saved=player:getModData()[key] or {};saved.profiles=profiles;player:getModData()[key]=saved
    awaiting[player]=true;return true
end

Events.OnTick.Add(function()
    local now=getTimestampMs()
    for player,deadline in pairs(expressionEdits) do
        if now>=deadline then flushExpression(player) end
    end
    for player,deadline in pairs(voiceSaveEdits) do
        if now>=deadline then
            voiceSaveEdits[player]=nil
            economy.saveLocal(player)
        end
    end
    for player in pairs(awaiting) do
        if GFL2CharacterRuntime.hasModelFailure(player) then
            awaiting[player]=nil
            problem(player,GFL2CharacterRuntime.status());voiceChanged(player)
        elseif GFL2CharacterRuntime.isReady(player) then
            awaiting[player]=nil;remember(player)
        end
    end
end)

local function choice(player, group, option)
    if problem(player, GFL2CharacterRuntime.setChoice(player, group, option)) then return false end
    remember(player);return true
end

local function preset(player, index)
    if problem(player, GFL2CharacterRuntime.setPreset(player, index)) then return false end
    remember(player);return true
end

local function reload(player)
    if problem(player, GFL2CharacterRuntime.reloadAppearance(player)) then return false end
    remember(player);return true
end

local function material(player,index,visible)
    if problem(player,GFL2CharacterRuntime.setMaterialVisible(player,index,visible)) then return false end
    remember(player);return true
end
local function reset(player)
    if problem(player,GFL2CharacterRuntime.resetAppearance(player)) then return false end
    remember(player);return true
end

local function resetPhysics(player)
    return not problem(player,GFL2CharacterRuntime.resetModelPhysics(player))
end


local function setBodyPhysics(player,enabled)
    if problem(player,GFL2CharacterRuntime.setModelPhysics(player,enabled))then return false end
    remember(player,true)
    return true
end

local function setChestStrength(player,strength,immediate)
    local clamped=math.max(0,math.min(100,math.floor((tonumber(strength) or 100)+0.5)))
    if problem(player,GFL2CharacterRuntime.setChestStrength(player,clamped)) then return false end
    if immediate then remember(player,true)
    else expressionEdits[player]=getTimestampMs()+250 end
    return true
end

local function getVoiceVolume(player)
    if GFL2VoiceEngine and GFL2VoiceEngine.getVolume then return GFL2VoiceEngine.getVolume(player) end
    local saved=player and player:getModData()[key]
    if type(saved)=="table" and saved.voiceVolume~=nil then return math.max(0,math.min(100,math.floor((tonumber(saved.voiceVolume) or 50)+0.5))) end
    return 50
end

local function setVoiceVolume(player,volume,immediate)
    local clamped=math.max(0,math.min(100,math.floor((tonumber(volume) or 50)+0.5)))
    if GFL2VoiceEngine and GFL2VoiceEngine.setVolume then
        clamped=GFL2VoiceEngine.setVolume(player,clamped)
    elseif player then
        local md=player:getModData()
        local saved=md[key]
        if type(saved)~="table" then saved={};md[key]=saved end
        saved.voiceVolume=clamped
    end
    if immediate then
        voiceSaveEdits[player]=nil
        economy.saveLocal(player)
    else
        voiceSaveEdits[player]=getTimestampMs()+250
    end
    return clamped
end

local function clearAppearance(player)
    expressionEdits[player]=nil
    voiceSaveEdits[player]=nil
    awaiting[player]=nil
    GFL2CharacterRuntime.disable(player)
    local saved=player:getModData()[key]
    player:getModData()[key] = saved and {profiles=saved.profiles,voiceVolume=saved.voiceVolume} or nil
    economy.saveLocal(player)
    voiceChanged(player)
end

multiplayer.onRejected=clearAppearance

local function disable(player)
    flushExpression(player)
    clearAppearance(player)
    publish(player, nil)
end

local function refresh(player)
    if problem(player,GFL2CharacterRuntime.refreshLibrary()) then return false end
    if isClient() then multiplayer.resync() end
    return true
end

local function restore(player)
    if not player or not GFL2CharacterRuntime then return end
    economy.restoreLocal(player)
    if isClient() then return end
    local saved = player:getModData()[key]
    if type(saved) == "table" and type(saved.model) == "string" then
        local message
        if saved.settings then message = GFL2CharacterRuntime.queueSettings(player, saved.model, saved.settings)
        else message = GFL2CharacterRuntime.queueRestore(player, saved.model, saved.appearance or "") end
        if not problem(nil, message) then publish(player, saved) end
    else
        publish(player, nil)
    end
end

local function open(player)
    window.open(player,{enable=enable,choice=choice,preset=preset,reload=reload,disable=disable,material=material,reset=reset,refresh=refresh,physicsReset=resetPhysics,bodyPhysics=setBodyPhysics,
        chestStrength=setChestStrength,getVoiceVolume=getVoiceVolume,setVoiceVolume=setVoiceVolume,
        expressionRemember=function(p)remember(p,true)end,
        expressionEdited=function(p)expressionEdits[p]=getTimestampMs()+250 end})
end

local function menu(playerIndex, context, worldObjects, test)
    if test then return ISWorldObjectContextMenu.setTest() end
    local player = getSpecificPlayer(playerIndex)
    if not player then return end
    if not GFL2CharacterRuntime then
        local unavailable = context:addOption(getText("ContextMenu_GFL2Character_JavaUnavailable"))
        unavailable.notAvailable = true
        return
    end
    context:addOption(getText("ContextMenu_GFL2Character_Menu"),player,open)
end

Events.OnFillWorldObjectContextMenu.Add(menu)
Events.OnGameStart.Add(function()
    awaiting={}
    expressionEdits={}
    window.closeAll()
    started = true
    if not GFL2CharacterRuntime then return end
    GFL2CharacterRuntime.clear()
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
    if GFL2CharacterRuntime then
        GFL2CharacterRuntime.disable(player)
    end
end)
if Events.OnMainMenuEnter then
    Events.OnMainMenuEnter.Add(function()
        started = false
        awaiting={}
        expressionEdits={}
        window.closeAll()
        if GFL2CharacterRuntime then GFL2CharacterRuntime.clear() end
    end)
end
Events.OnDisconnect.Add(function()
    awaiting={}
    expressionEdits={}
    window.closeAll()
    started = false
    if GFL2CharacterRuntime then GFL2CharacterRuntime.clear() end
end)
