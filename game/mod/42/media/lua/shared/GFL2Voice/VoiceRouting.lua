-- Shared restoration only; character catalogs, settings and playback stay separate.
GFL2VoiceRouting = GFL2VoiceRouting or {}
local R = GFL2VoiceRouting
R.prefixBackups=R.prefixBackups or {}
local backups=R.prefixBackups
local voiceFamily="GFL2Pmx"
local backupSuffix="OriginalVoicePrefix"
local silentSuffix="Silent"
-- Read-only names for restoration from pre-migration saves and optional providers.
local previousProvider=string.char(65,105,109,111,104)
local previousFamily=previousProvider.."PMX"
local oldCards={Cheyanne=true,Asteria=true,Faelynn=true,Laiya=true}

function R.previousProvider(name)
    return rawget(_G,previousProvider..name)
end

local function olderBackup(prefix)
    if type(prefix)~="string" or prefix:sub(-#silentSuffix)~=silentSuffix then return end
    local stem=prefix:sub(1,-#silentSuffix-1)
    if stem:sub(1,#previousFamily)==previousFamily then return stem..backupSuffix,true end
    if stem:sub(1,#previousProvider)==previousProvider and oldCards[stem:sub(#previousProvider+1)] then
        return stem..backupSuffix,false
    end
end
function R.registerPrefix(prefix,backupKey)
    if type(prefix)~="string" or prefix=="" or type(backupKey)~="string" or backupKey=="" then return false end
    if backups[prefix] and backups[prefix]~=backupKey then return false end
    backups[prefix]=backupKey
    return true
end
function R.originalPrefix(player, prefix)
    local md = player:getModData()
    local seen={}
    while not seen[prefix or false] do
        seen[prefix or false]=true
        local key = backups[prefix]
        local external=R.previousProvider("DollVoiceRouting")
        if not key and external and external.prefixBackups then key=external.prefixBackups[prefix] end
        if not key then key=olderBackup(prefix) end
        if not key then return prefix or (player:isFemale() and "VoiceFemale" or "VoiceMale") end
        prefix = md[key]
        if not prefix then break end
    end
    return player:isFemale() and "VoiceFemale" or "VoiceMale"
end
function R.restoreRetired(player)
    local descriptor=player:getDescriptor()
    if not descriptor then return end
    local prefix=descriptor:getVoicePrefix()
    local _,owned=olderBackup(prefix)
    if owned then descriptor:setVoicePrefix(R.originalPrefix(player,prefix)) end
    local data=player:getModData()
    local removed={}
    for key in pairs(data) do
        if type(key)=="string" and key:sub(1,#previousFamily)==previousFamily and key:sub(-#backupSuffix)==backupSuffix then
            removed[#removed+1]=key
        end
    end
    for _,key in ipairs(removed) do data[key]=nil end
end
function R.syncExternal()
    local external=R.previousProvider("DollVoiceRouting")
    if external and external.registerPrefix then
        for prefix,key in pairs(backups) do
            if prefix:sub(1,#voiceFamily)==voiceFamily and not external.registerPrefix(prefix,key) then return false end
        end
    end
    return true
end
-- Only the primary local listener receives costume audio, including in MP.
function R.isLocalListener(player)
    return not isServer() and player ~= nil and player == getSpecificPlayer(0)
end
function R.isLocalCompletion(method, hasComplete)
    if isClient() then return method == "perform" end
    return method == (hasComplete and "complete" or "perform")
end
return R
