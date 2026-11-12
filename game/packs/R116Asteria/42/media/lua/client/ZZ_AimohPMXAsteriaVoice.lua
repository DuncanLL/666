require "AimohPMXAsteriaVoice/Interface"
require "AimohPMXAsteriaVoice/Core"
require "AimohPMXAsteriaVoice/Native"
require "AimohPMXVoice/VoiceEngine"
AimohPMXVoiceEngine.install({id="Asteria",facade=AimohPMXAsteriaVoice,core=AimohPMXAsteriaVoiceCore,native=AimohPMXAsteriaNativeVoice,resolve=AimohPMXAsteriaVoice.resolveCue,captionTag="aimohpmxasteriavoice",logTag="[AimohPMXAsteriaVoice]",cues={sneakShout="SneakShout_A",shout={"Shout_A","Shout_B","Shout_C"}},killCue="Kill_A"})
