require "AimohPMXFaelynnVoice/Data"
AimohPMXFaelynnVoice=AimohPMXFaelynnVoice or {}
local V=AimohPMXFaelynnVoice
V.characterId="Faelynn"
function V.resolveCue(player,id)
 if not AimohPMXVoiceIdentity.isWearer(player,"Faelynn") then return nil,"wrong-character" end
 return AimohPMXFaelynnVoiceData.clips[id]
end
return V
