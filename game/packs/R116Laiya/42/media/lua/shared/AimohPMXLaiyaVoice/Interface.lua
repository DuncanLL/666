require "AimohPMXLaiyaVoice/Data"
AimohPMXLaiyaVoice=AimohPMXLaiyaVoice or {}
local V=AimohPMXLaiyaVoice
V.characterId="Laiya"
function V.resolveCue(player,id)
 if not AimohPMXVoiceIdentity.isWearer(player,"Laiya") then return nil,"wrong-character" end
 return AimohPMXLaiyaVoiceData.clips[id]
end
return V
