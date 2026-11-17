require "GFL2Voice/FullVoiceCore"
require "GFL2Voice/NativeVoice"
require "GFL2Voice/SilentData"
require "GFL2Voice/VoiceEngine"

GFL2CatalogVoice={}

function GFL2CatalogVoice.install(id,modelId,clips)
    local prefix="GFL2Pmx"..id
    GFL2VoiceIdentity.registerVoice(modelId,id)
    for cue,clip in pairs(clips) do
        clip.sound=prefix.."_"..cue
    end
    local native=GFL2PmxNativeVoice.create({
        prefix=prefix.."Silent",suffixes=GFL2PmxSilentVoiceData.suffixes,
    },prefix.."OriginalVoicePrefix")
    return GFL2VoiceEngine.install({
        id=id,facade={},core=GFL2PmxFullVoiceCore.create(),native=native,
        resolve=function(_,cue) return clips[cue] end,
        captionTag=prefix:lower().."voice",logTag="["..prefix.."Voice]",
        cues={shout="Shout_A",sneakShout="SneakShout_A"},killCue="Kill_A",
    })
end

return GFL2CatalogVoice
