require "GFL2PmxFaelynnVoice/Data"
GFL2PmxFaelynnVoice=GFL2PmxFaelynnVoice or {}
local V=GFL2PmxFaelynnVoice
V.characterId="Faelynn"
function V.resolveCue(player,id)
 if not GFL2VoiceIdentity.isWearer(player,"Faelynn") then return nil,"wrong-character" end
 return GFL2PmxFaelynnVoiceData.clips[id]
end
return V
