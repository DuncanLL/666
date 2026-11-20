require "GFL2PmxFaelynnVoice/Interface"
require "GFL2PmxFaelynnVoice/Core"
require "GFL2PmxFaelynnVoice/Native"
require "GFL2Voice/VoiceEngine"
GFL2VoiceEngine.install({id="Faelynn",facade=GFL2PmxFaelynnVoice,core=GFL2PmxFaelynnVoiceCore,native=GFL2PmxFaelynnNativeVoice,resolve=GFL2PmxFaelynnVoice.resolveCue,captionTag="gfl2pmxfaelynnvoice",logTag="[GFL2PmxFaelynnVoice]",cues={shout="Shout_A",sneakShout="SneakShout_A"},killCue="Kill_A"})
GFL2VoiceIdentity.registerVoice("pmx-b6fa1b6b340d0fec","Faelynn")
