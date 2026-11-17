require "GFL2PmxLaiyaVoice/Data"
GFL2PmxLaiyaVoice=GFL2PmxLaiyaVoice or {}
local V=GFL2PmxLaiyaVoice
V.characterId="Laiya"
function V.resolveCue(player,id)
 if not GFL2VoiceIdentity.isWearer(player,"Laiya") then return nil,"wrong-character" end
 return GFL2PmxLaiyaVoiceData.clips[id]
end
return V
