-- Shared restoration only; character catalogs, settings and playback stay separate.
AimohPMXVoiceRouting = AimohPMXVoiceRouting or {}
local R = AimohPMXVoiceRouting
R.prefixBackups=R.prefixBackups or {
    AimohCheyanneSilent="AimohCheyanneOriginalVoicePrefix",
    AimohAsteriaSilent="AimohAsteriaOriginalVoicePrefix",
    AimohFaelynnSilent="AimohFaelynnOriginalVoicePrefix",
    AimohLaiyaSilent="AimohLaiyaOriginalVoicePrefix",
}
local backups=R.prefixBackups
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
        local external=rawget(_G,"AimohDollVoiceRouting")
        if not key and external and external.prefixBackups then key=external.prefixBackups[prefix] end
        if not key then return prefix or (player:isFemale() and "VoiceFemale" or "VoiceMale") end
        prefix = md[key]
        if not prefix then break end
    end
    return player:isFemale() and "VoiceFemale" or "VoiceMale"
end
function R.syncExternal()
    local external=rawget(_G,"AimohDollVoiceRouting")
    if external and external.registerPrefix then
        for prefix,key in pairs(backups) do
            if prefix:sub(1,8)=="AimohPMX" and not external.registerPrefix(prefix,key) then return false end
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
