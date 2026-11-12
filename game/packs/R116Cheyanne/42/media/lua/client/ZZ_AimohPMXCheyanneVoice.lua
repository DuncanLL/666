require "AimohPMXCheyanneVoice/Data"
AimohPMXCheyanneVoice=AimohPMXCheyanneVoice or {}
require "AimohPMXCheyanneVoice/Core"
require "AimohPMXCheyanneVoice/Native"
require "AimohPMXVoice/VoiceEngine"
AimohPMXVoiceEngine.install({id="Cheyanne",facade=AimohPMXCheyanneVoice,core=AimohPMXCheyanneVoiceCore,native=AimohPMXCheyanneNativeVoice,resolve=function(_,cue) return AimohPMXCheyanneVoiceData.clips[cue] end,captionTag="aimohpmxcheyannevoice",logTag="[AimohPMXCheyanneVoice]",cues={sneakShout="SneakShout_A",shout={"Shout_A","Shout_B","Shout_C"}},killCue="Kill_A"})
