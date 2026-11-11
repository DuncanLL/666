require "AimohPMXFaelynnVoice/Interface"
require "AimohPMXFaelynnVoice/Core"
require "AimohPMXFaelynnVoice/Native"
require "AimohPMXVoice/VoiceEngine"
AimohPMXVoiceEngine.install({id="Faelynn",facade=AimohPMXFaelynnVoice,core=AimohPMXFaelynnVoiceCore,native=AimohPMXFaelynnNativeVoice,resolve=AimohPMXFaelynnVoice.resolveCue,captionTag="aimohpmxfaelynnvoice",logTag="[AimohPMXFaelynnVoice]",cues={shout={}}})
