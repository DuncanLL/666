require "AimohPMXVoice/FullVoiceCore"
require "AimohPMXVoice/NativeVoice"
require "AimohPMXVoice/SilentData"
require "AimohPMXVoice/VoiceEngine"

AimohPMXCatalogVoice={}

function AimohPMXCatalogVoice.install(id,modelId,clips)
    local prefix="AimohPMX"..id
    AimohPMXVoiceIdentity.registerVoice(modelId,id)
    for cue,clip in pairs(clips) do
        clip.sound=prefix.."_"..cue
    end
    local native=AimohPMXNativeVoice.create({
        prefix=prefix.."Silent",suffixes=AimohPMXSilentVoiceData.suffixes,
    },prefix.."OriginalVoicePrefix")
    return AimohPMXVoiceEngine.install({
        id=id,facade={},core=AimohPMXFullVoiceCore.create(),native=native,
        resolve=function(_,cue) return clips[cue] end,
        captionTag=prefix:lower().."voice",logTag="["..prefix.."Voice]",
        cues={shout="Shout_A",sneakShout="SneakShout_A"},killCue="Kill_A",
    })
end

return AimohPMXCatalogVoice
