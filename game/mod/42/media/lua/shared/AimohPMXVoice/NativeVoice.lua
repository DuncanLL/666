require "AimohPMXVoice/VoiceRouting"
AimohPMXNativeVoice=AimohPMXNativeVoice or {}
function AimohPMXNativeVoice.create(D,backupKey)
assert(AimohPMXVoiceRouting.registerPrefix(D.prefix,backupKey),"native voice prefix conflict")
local N={}
local function stopPrefix(player, prefix)
    if not prefix then return end
    local emitter = player:getEmitter()
    for i=1,#D.suffixes do emitter:stopSoundByName(prefix..D.suffixes[i]) end
end

function N.stopBreath(player, state)
    if state.breathHandle then player:getEmitter():stopSoundLocal(state.breathHandle) end
    state.breathHandle=nil
    state.breathCue=nil
end

function N.pauseBreath(player, state, now)
    N.stopBreath(player,state)
    state.breathNext=math.max(state.breathNext or 0,now+750)
end

function N.enable(player, state)
    local desc=player:getDescriptor()
    if not desc then return end
    local md=player:getModData()
    local prefix=desc:getVoicePrefix()
    if not state.nativeMuted then
        prefix=AimohPMXVoiceRouting.originalPrefix(player,prefix)
        state.originalVoicePrefix=prefix
        md[backupKey]=prefix
        stopPrefix(player,prefix)
    end
    desc:setVoicePrefix(D.prefix)
    state.nativeMuted=true
    state.nativeSaveRestored=false
end

function N.disable(player, state)
    N.stopBreath(player,state)
    local desc=player:getDescriptor()
    local md=player:getModData()
    local original=state.originalVoicePrefix or md[backupKey]
    if original then original=AimohPMXVoiceRouting.originalPrefix(player,original) end
    if desc and desc:getVoicePrefix()==D.prefix then
        desc:setVoicePrefix(original or (player:isFemale() and "VoiceFemale" or "VoiceMale"))
    end
    if state.nativeMuted or original then stopPrefix(player,D.prefix) end
    md[backupKey]=nil
    state.originalVoicePrefix=nil
    state.nativeMuted=false
    state.nativeSaveRestored=false
    state.breathMode=nil
    state.breathNext=0
end

-- Keep ordinary save descriptors native where the game's save events cover them.
-- The mod-data backup also recovers a costume prefix from independent player saves.
function N.beforeSave(states)
    for player,state in pairs(states) do
        if state.nativeMuted then
            local desc=player:getDescriptor()
            if desc and desc:getVoicePrefix()==D.prefix then
                desc:setVoicePrefix(state.originalVoicePrefix)
                state.nativeSaveRestored=true
            end
        end
    end
end

function N.afterSave(states)
    for player,state in pairs(states) do
        if state.nativeMuted and state.nativeSaveRestored then N.enable(player,state) end
    end
end

-- All breathing is disabled by user request, independent of endurance level.
function N.updateBreath(player, state, now, level, paused)
    N.stopBreath(player,state)
    state.breathMode=nil
    state.breathNext=0
end


return N
end
return AimohPMXNativeVoice
