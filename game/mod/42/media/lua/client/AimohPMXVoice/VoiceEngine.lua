require "AimohPMXVoice/VoiceRouting"
require "AimohPMXVoice/Identity"
require "AimohPMXVoice/Silent"
require "ISUI/ISEmoteRadialMenu"
require "ISUI/ISDPadWheels"
if AimohPMXVoiceEngine then return AimohPMXVoiceEngine end
AimohPMXVoiceEngine={version="2.2.0-four-voices-head-captions-silence",instances={},order={}}
local M=AimohPMXVoiceEngine
local silentPlayer,silentState,silentSavePending=nil,nil,false
local silentStates=setmetatable({}, {__mode="k"})
function M.install(profile)
    if M.instances[profile.id] then return M.instances[profile.id] end
    local V=profile.facade
    V.version=M.version;V.playbackVersion=M.version
    local Core=profile.core
    local Native=profile.native
local savePending=false
local cfg=Core.defaults
local active=nil
local states=setmetatable({}, {__mode="k"})
local shoutSuffixes={"ShoutHey","ShoutMegaphoneHey","WhisperPsst",
    "WhisperHey","WhisperMegaphonePsst","WhisperMegaphoneHey"}
local failureLogged={}

for i=1,#Core.rules do
    local rule=Core.rules[i]
    rule.moodle=MoodleType[rule.enum]
    if not rule.moodle then print(profile.logTag.." Missing moodle: "..rule.enum) end
end

local function nowMs() return getTimestampMs() end
local function belongs(player)
    return AimohPMXVoiceRouting.isLocalListener(player)
end

local function wearing(player)
    return AimohPMXVoiceIdentity.isWearer(player,profile.id)
end

local function stateFor(player)
    local state=states[player]
    if not state then
        state=Core.newState(nowMs(),nowMs()+player:getPlayerNum()*101)
        state.nextPoll=0
        state.nextTick=0
        state.wasAsleep=player:isAsleep()
        states[player]=state
    end
    return state
end

local function clearCaption(player,state)
    if state.caption and player:getSayLine()==state.caption then player:SayDebug(0,"") end
    state.caption=nil
end

local function stopOwned(player,state)
    if not state then return end
    Native.stopBreath(player,state)
    if state.handle then player:getEmitter():stopSoundLocal(state.handle) end
    state.handle=nil
    clearCaption(player,state)
    Core.finish(state)
end

local function caption(player,state,text,now)
    -- Existing overhead captions; no additional first-person subtitle UI.
    player:SayDebug(0,"")
    player:addLineChatElement(text,1,1,1,UIFont.Small,30,profile.captionTag)
    state.caption=text
    state.nextCaption=now+1000
end

local function observe(player,state)
    local moodles=player:getMoodles()
    return Core.observe(state,function(rule)
        return rule.moodle and moodles:getMoodleLevel(rule.moodle) or 0
    end)
end

local function play(player,state,candidate,now,allowAsleep,alreadyEligible)
    if not belongs(player) or active~=player or player:isDead() then return false end
    if player:isAsleep() and not allowAsleep then return false end
    if not alreadyEligible then candidate=Core.propose(state,candidate,now,cfg) end
    if not candidate then return false end
    local clip=profile.resolve(player,candidate.cue)
    if not clip then return false end
    stopOwned(player,state)
    Native.pauseBreath(player,state,now)
    local handle=player:playSoundLocal(clip.sound)
    if not handle or handle==0 then
        if not failureLogged[clip.sound] then
            failureLogged[clip.sound]=true
            print(profile.logTag.." Audio could not start: "..clip.sound)
        end
        return false
    end
    state.handle=handle
    state.startedAt=now
    state.currentKind=candidate.kind
    Core.commit(state,candidate,now,clip.duration,cfg)
    caption(player,state,clip.chinese,now)
    print(profile.logTag.." PLAY "..candidate.cue.." "..candidate.kind.." priority="..candidate.priority)
    return true
end

function V.onClothingUpdated(player)
    if not belongs(player) then return end
    local state=stateFor(player)
    if wearing(player) then
        Native.enable(player,state)
        if player:isDead() then
            stopOwned(player,state)
            if active==player then active=nil end
            return
        end
        if active~=player then
            active=player
            state.initialized=false
            state.nextPoll=0
            state.nextOrdinary=math.max(state.nextOrdinary,nowMs()+cfg.startupQuietMs)
            state.nextReport=nowMs()+60000
            print(profile.logTag.." Costume voice enabled")
        end
    else
        Native.disable(player,state)
        if active==player then
            stopOwned(player,state)
            active=nil
            print(profile.logTag.." Costume voice disabled")
        end
    end
end

function V.afterKill(player)
    if not profile.killCue then return end
    if active~=player or not belongs(player) or player:isDead() or player:isAsleep() then return end
    if ZombRand(100)>=8 or not wearing(player) then return end
    play(player,stateFor(player),{cue=profile.killCue,priority=10,key="kill",kind="kill",chance=1},nowMs(),false)
end

function V.afterCallout(player)
    if active~=player or not belongs(player) or player:isDead() then return end
    local descriptor=player:getDescriptor()
    if not descriptor then return end
    local prefix=descriptor:getVoicePrefix()
    local emitter=player:getEmitter()
    local called=false
    for i=1,#shoutSuffixes do
        local suffix=shoutSuffixes[i]
        if emitter:isPlaying(prefix..suffix) then
            called=true
            player:stopPlayerVoiceSound(suffix)
        end
    end
    -- Only a real completed vanilla callout with a vocal triggers this replacement.
    -- A blocked Q, open radial menu, vehicle restriction, or instrument is not guessed.
    if not called then return end
    local state=stateFor(player)
    local cue=player:isSneaking() and profile.cues.sneakShout or Core.pick(state,profile.cues.shout,"shout")
    play(player,state,{cue=cue,priority=110,key="shout",kind="manual",chance=1},nowMs(),false)
end

function V.tick()
    if savePending then Native.afterSave(states); savePending=false end
    local player=active
    if not player then return end -- Unequipped: no native API calls and no polling.
    if not belongs(player) then
        stopOwned(player,states[player]); Native.disable(player,states[player]); active=nil
        return
    end
    local state=states[player]
    local now=nowMs()
    if now<state.nextTick then return end
    state.nextTick=now+cfg.tickMs
    if now>=state.nextPoll and not wearing(player) then
        stopOwned(player,state);Native.disable(player,state);active=nil;return
    end
    if player:isDead() then stopOwned(player,state);Native.disable(player,state);active=nil;return end
    if state.handle then
        if now>=state.speakingUntil or
            (now-state.startedAt>=750 and not player:getEmitter():isPlaying(state.handle)) then
            stopOwned(player,state)
        elseif state.caption and now>=state.nextCaption then
            -- Same native text object is reused; this does not append more chat lines.
            player:SayDebug(0,state.caption)
            state.nextCaption=now+1000
        end
    end
    local asleep=player:isAsleep()
    if asleep then
        if state.handle then stopOwned(player,state) end
        state.wasAsleep=true
        return
    elseif state.wasAsleep then
        state.wasAsleep=false
        state.initialized=false
        state.nextPoll=0
    end
    if now<state.nextPoll then return end
    state.nextPoll=now+cfg.pollMs
    local changed=observe(player,state)
    local speed=UIManager.getSpeedControls()
    local accelerated=speed and speed:getCurrentGameSpeed()>1
    state.accelerated=accelerated
    local candidate=nil
    local function offer(offered,eligible)
        if not offered or (accelerated and offered.priority<80) then return end
        candidate=Core.better(candidate,eligible and offered or Core.propose(state,offered,now,cfg))
    end
    offer(changed)
    offer(Core.current(state,now,cfg),true)
    if candidate then
        play(player,state,candidate,now,false,true)
    end
    if now>=state.nextReport then
        state.nextReport=now+60000
        print(profile.logTag.." STATUS samples="..state.samples.." auto="..state.autoSpoken..
            " Q="..state.manualSpoken.." highest="..tostring(state.maxKey)..":"..state.maxPriority..
            " nextAuto="..math.max(0,math.ceil((state.nextOrdinary-now)/1000)).."s block="..tostring(state.lastReject))
    end
end

-- Diagnostics are callable in debug tools but are not added to ordinary game menus.
function V.getDiagnostics()
    if not active then return {active=false,version=V.version} end
    local state=states[active]
    return {active=true,version=V.version,samples=state.samples,moodleReads=state.moodleReads,
        spoken=state.spoken,autoSpoken=state.autoSpoken,manualSpoken=state.manualSpoken,killSpoken=state.killSpoken,
        currentCue=state.currentCue,maxPriority=state.maxPriority,
        highest=state.maxKey,levels=state.levels,lastBlock=state.lastReject,
        nextAutoMs=math.max(0,state.nextOrdinary-nowMs()),nativeMuted=state.nativeMuted,
        breathCue=state.breathCue,breathPlayed=state.breathPlayed or 0}
end

function V.isActive(player) return active==player and belongs(player) end
function V.beforeSave() Native.beforeSave(states);savePending=true end
function V.afterSave() Native.afterSave(states);savePending=false end
function V.deactivate()
    if active then local player=active;stopOwned(player,states[player]);Native.disable(player,states[player]);active=nil end
end
function V.onDeath(player) if active==player then V.deactivate() end end
function V.onDamage(player)
    if active~=player then return end
    local state=states[player];local now=nowMs()
    if not state.lastDamageCheck or now-state.lastDamageCheck>=500 then state.lastDamageCheck=now;state.nextPoll=0 end
end
M.instances[profile.id]=V;M.order[#M.order+1]=V
return V
end

local function listener(player)
    for _,voice in ipairs(M.order) do if voice.isActive(player) then return voice end end
end
local function dispatch(method,...)
    for _,voice in ipairs(M.order) do voice[method](...) end
end
local function callout(player) local voice=listener(player);if voice then voice.afterCallout(player) end end
local originalKeyReleased=ISEmoteRadialMenu.onKeyReleased
ISEmoteRadialMenu.onKeyReleased=function(key)
    originalKeyReleased(key)
    if getCore():isKey(KeybindId.SHOUT,key) then callout(getSpecificPlayer(0)) end
end
local originalEmote=ISEmoteRadialMenu.emote
ISEmoteRadialMenu.emote=function(self,emote) originalEmote(self,emote);if emote=="shout" then callout(self.character) end end
local originalDPad=ISDPadWheels.onShout
ISDPadWheels.onShout=function(player) originalDPad(player);callout(player) end
Events.OnZombieDead.Add(function(zombie)
    if not zombie then return end
    local player=zombie:getAttackedBy()
    local voice=listener(player)
    if voice then voice.afterKill(player) end
end)
Events.OnSave.Add(function() dispatch("beforeSave");AimohPMXSilentVoice.beforeSave(silentStates);silentSavePending=true end)
Events.OnPostSave.Add(function() dispatch("afterSave");AimohPMXSilentVoice.afterSave(silentStates);silentSavePending=false end)
local pending,nextCheck=nil,0
local function stopSilent()
    if silentPlayer then AimohPMXSilentVoice.disable(silentPlayer,silentState) end
    silentPlayer=nil;silentState=nil
end
local function needsSilent(player)
    if not AimohPMXVoiceRouting.isLocalListener(player) or not AimohPMXVoiceIdentity.isPmxWearer(player) then return false end
    local voice=AimohPMXVoiceIdentity.voiceForModel(AimohPMXPreview.modelId(player))
    return not voice or not M.instances[voice]
end
local function syncSilent(player)
    if silentPlayer and (silentPlayer~=player or not needsSilent(player)) then stopSilent() end
    if needsSilent(player) then
        silentStates[player]=silentStates[player] or {}
        silentPlayer=player;silentState=silentStates[player]
        AimohPMXSilentVoice.enable(player,silentState)
    end
end
function M.watch(player,id)
    if not AimohPMXVoiceRouting.isLocalListener(player) then return end
    if AimohPMXVoiceRouting.isLocalListener(player) and type(id)=="string" and id~="" then
        pending={player=player,id=id};nextCheck=0
    else pending=nil end
end
function M.modelChanged(player)
    if not AimohPMXVoiceRouting.isLocalListener(player) then return end
    pending=nil
    AimohPMXVoiceRouting.syncExternal()
    if silentPlayer==player and not needsSilent(player) then stopSilent() end
    dispatch("onClothingUpdated",player)
    syncSilent(player)
    if AimohPMXPreview then
        local id=AimohPMXPreview.modelId(player)
        if not AimohPMXPreview.isReady(player) then M.watch(player,id) end
    end
end
function M.stopAll()
    pending=nil
    for _,voice in ipairs(M.order) do voice.deactivate() end
    stopSilent()
end
local function waitForModel()
    if silentSavePending then AimohPMXSilentVoice.afterSave(silentStates);silentSavePending=false end
    if not pending and not silentPlayer then return end
    local now=getTimestampMs();if now<nextCheck then return end
    nextCheck=now+1000
    if silentPlayer and not needsSilent(silentPlayer) then stopSilent() end
    if not pending then return end
    local player,id=pending.player,pending.id
    if player:isDead() then pending=nil;return end
    if not AimohPMXVoiceRouting.isLocalListener(player) then pending=nil;return end
    local saved=player:getModData().AimohPMXPreview
    if type(saved)~="table" or saved.model~=id then pending=nil;return end
    if AimohPMXPreview then
        local current=AimohPMXPreview.modelId(player)
        if current==id and AimohPMXPreview.isReady(player) then
            M.modelChanged(player)
        elseif current~="" and current~=id then pending=nil end
    end
end
Events.OnTick.Add(function() waitForModel();dispatch("tick") end)
Events.OnClothingUpdated.Add(function(player) M.modelChanged(player) end)
local function startPlayer(player)
    if not player then return end
    AimohPMXVoiceRouting.syncExternal()
    M.modelChanged(player)
    local saved=player:getModData().AimohPMXPreview
    if type(saved)=="table" then M.watch(player,saved.model) end
end
Events.OnCreatePlayer.Add(function(index,player) if index==0 then startPlayer(player) end end)
Events.OnGameStart.Add(function() startPlayer(getSpecificPlayer(0)) end)
if Events.OnMainMenuEnter then Events.OnMainMenuEnter.Add(M.stopAll) end
Events.OnDisconnect.Add(M.stopAll)
Events.OnPlayerDeath.Add(function(player) dispatch("onDeath",player);if silentPlayer==player then stopSilent() end end)
Events.OnPlayerGetDamage.Add(function(player) dispatch("onDamage",player) end)
return M
