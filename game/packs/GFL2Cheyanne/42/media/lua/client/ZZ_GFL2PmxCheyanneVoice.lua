require "GFL2PmxCheyanneVoice/Data"
GFL2PmxCheyanneVoice=GFL2PmxCheyanneVoice or {}
require "GFL2PmxCheyanneVoice/Core"
require "GFL2PmxCheyanneVoice/Native"
require "GFL2Voice/VoiceEngine"
GFL2VoiceEngine.install({id="Cheyanne",facade=GFL2PmxCheyanneVoice,core=GFL2PmxCheyanneVoiceCore,native=GFL2PmxCheyanneNativeVoice,resolve=function(_,cue) return GFL2PmxCheyanneVoiceData.clips[cue] end,captionTag="gfl2pmxcheyannevoice",logTag="[GFL2PmxCheyanneVoice]",cues={sneakShout="SneakShout_A",shout={"Shout_A","Shout_B","Shout_C"}},killCue="Kill_A"})
