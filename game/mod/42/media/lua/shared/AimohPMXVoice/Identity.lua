-- Voice assignments are model data. Unlisted models do not receive a voice.
AimohPMXVoiceIdentity=AimohPMXVoiceIdentity or {}
local I=AimohPMXVoiceIdentity
local voices={["pmx-412bd037301ebe18"]="Asteria",["pmx-b0dd2e3cfd1ab80c"]="Asteria",["pmx-48650a04a35b40e0"]="Cheyanne",
 ["pmx-abe3eaaaa9d04264"]="Laiya",["pmx-0af216265749a22d"]="Laiya",["pmx-6eb458b3d9c9a094"]="Laiya",["pmx-293917f24c2bd387"]="Faelynn"}
function I.voiceForModel(id) return voices[id] end
function I.registerVoice(modelId,voice)
    assert(not voices[modelId] or voices[modelId]==voice,"conflicting voice for model "..modelId)
    voices[modelId]=voice
end
function I.isPmxWearer(player)
    if not player or player:isDead() or not AimohPMXPreview or not AimohPMXPreview.isReady(player) then return false end
    -- An old card already owns its voice; avoid a second driver or stealing its prefix.
    local chars=rawget(_G,"AimohDollLibraryCharacters")
    local engine=rawget(_G,"AimohDollVoiceEngine")
    if chars and engine and engine.instances then
        local card=chars.forPlayer(player)
        if card and engine.instances[card.id] then return false end
    end
    return true
end
function I.isWearer(player,voice)
    return I.isPmxWearer(player) and voices[AimohPMXPreview.modelId(player)]==voice
end
return I
