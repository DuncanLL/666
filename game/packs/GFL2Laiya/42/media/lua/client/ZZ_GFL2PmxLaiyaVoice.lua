require "GFL2PmxLaiyaVoice/Interface"
require "GFL2PmxLaiyaVoice/Core"
require "GFL2PmxLaiyaVoice/Native"
require "GFL2Voice/VoiceEngine"
GFL2VoiceEngine.install({id="Laiya",facade=GFL2PmxLaiyaVoice,core=GFL2PmxLaiyaVoiceCore,native=GFL2PmxLaiyaNativeVoice,resolve=GFL2PmxLaiyaVoice.resolveCue,captionTag="gfl2pmxlaiyavoice",logTag="[GFL2PmxLaiyaVoice]",cues={shout="Shout_A",sneakShout="SneakShout_A"},killCue="Kill_A"})
