require "GFL2PmxAsteriaVoice/Interface"
require "GFL2PmxAsteriaVoice/Core"
require "GFL2PmxAsteriaVoice/Native"
require "GFL2Voice/VoiceEngine"
GFL2VoiceEngine.install({id="Asteria",facade=GFL2PmxAsteriaVoice,core=GFL2PmxAsteriaVoiceCore,native=GFL2PmxAsteriaNativeVoice,resolve=GFL2PmxAsteriaVoice.resolveCue,captionTag="gfl2pmxasteriavoice",logTag="[GFL2PmxAsteriaVoice]",cues={sneakShout="SneakShout_A",shout={"Shout_A","Shout_B","Shout_C"}},killCue="Kill_A"})
