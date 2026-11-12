require "AimohPMXLaiyaVoice/Interface"
require "AimohPMXLaiyaVoice/Core"
require "AimohPMXLaiyaVoice/Native"
require "AimohPMXVoice/VoiceEngine"
AimohPMXVoiceEngine.install({id="Laiya",facade=AimohPMXLaiyaVoice,core=AimohPMXLaiyaVoiceCore,native=AimohPMXLaiyaNativeVoice,resolve=AimohPMXLaiyaVoice.resolveCue,captionTag="aimohpmxlaiyavoice",logTag="[AimohPMXLaiyaVoice]",cues={shout={}}})
