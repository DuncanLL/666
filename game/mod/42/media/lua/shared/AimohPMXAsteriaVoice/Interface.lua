-- Identity/catalog API; playback is supplied by the independent client driver.
require "AimohPMXVoice/Identity"
require "AimohPMXAsteriaVoice/Data"
AimohPMXAsteriaVoice = AimohPMXAsteriaVoice or {}
local V = AimohPMXAsteriaVoice
local Identity = AimohPMXVoiceIdentity
local catalog = {schema=1, characterId="Asteria", clips={}, events={}}
local validEvents = {state=true,callout=true,kill=true}
V.apiVersion = 1
V.characterId = "Asteria"
V.soundPrefix = "AimohPMXAsteria_"

function V.isWearer(player)
    return Identity.isWearer(player,"Asteria")
end

function V.registerCatalog(data)
    if type(data) ~= "table" or data.schema ~= 1 or data.characterId ~= "Asteria"
        or type(data.clips) ~= "table" or type(data.events) ~= "table" then
        return false, "invalid-asteria-catalog"
    end
    local nextCatalog = {schema=1, characterId="Asteria", clips={}, events={}}
    for id, clip in pairs(data.clips) do
        if type(id) ~= "string" or not id:match("^[%w_%-]+$") or type(clip) ~= "table"
            or clip.sound ~= V.soundPrefix .. id or type(clip.duration) ~= "number"
            or clip.duration <= 0 or clip.duration ~= clip.duration or clip.duration == math.huge
            or type(clip.chinese) ~= "string" or clip.chinese == "" then
            return false, "invalid-asteria-clip"
        end
        nextCatalog.clips[id] = {sound=clip.sound, duration=clip.duration, chinese=clip.chinese}
    end
    for event, ids in pairs(data.events) do
        if not validEvents[event] or type(ids) ~= "table" or #ids == 0 then
            return false, "invalid-asteria-event"
        end
        local copy = {}
        for index, id in pairs(ids) do
            if type(index) ~= "number" or index < 1 or index > #ids or index % 1 ~= 0
                or type(id) ~= "string" or not nextCatalog.clips[id] then
                return false, "unresolved-asteria-event-clip"
            end
            copy[index] = id
        end
        nextCatalog.events[event] = copy
    end
    catalog = nextCatalog
    return true
end

function V.resolveCue(player, id)
    if not V.isWearer(player) then return nil, "wrong-character" end
    local clip = catalog.clips[id]
    if not clip then return nil, "asteria-cue-not-installed" end
    return {sound=clip.sound, duration=clip.duration, chinese=clip.chinese}
end

function V.cuesForEvent(player, event)
    if not V.isWearer(player) then return nil, "wrong-character" end
    if not validEvents[event] then return nil, "unknown-event" end
    local ids = catalog.events[event]
    if not ids then return nil, "asteria-event-not-installed" end
    local copy = {}
    for i, id in ipairs(ids) do copy[i] = id end
    return copy
end

function V.getStatus()
    local count = 0
    for _ in pairs(catalog.clips) do count = count + 1 end
    return {apiVersion=V.apiVersion, characterId="Asteria", clipCount=count,
        catalogReady=count > 0, playbackInstalled=V.playbackVersion ~= nil,
        status=V.playbackVersion and "installed-driver" or (count > 0 and "catalog-ready" or "awaiting-audio")}
end

local ok, reason = V.registerCatalog(AimohPMXAsteriaVoiceData)
if not ok then print("[AimohPMXAsteriaVoice] " .. reason) end
return V
