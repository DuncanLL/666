local events={}
Events={}
for _,name in ipairs({"OnZombieDead","OnSave","OnPostSave","OnTick","OnClothingUpdated",
    "OnCreatePlayer","OnGameStart","OnMainMenuEnter","OnDisconnect","OnPlayerDeath","OnPlayerGetDamage"}) do
    local key=name
    Events[key]={Add=function(fn)events[key]=fn end}
end
AimohPMXVoiceEngine=nil
MoodleType=setmetatable({},{__index=function(_,key)return key end})
KeybindId={SHOUT=17}
function getCore()return {isKey=function(_,a,b)return a==b end}end
function getTimestampMs()return 10000 end
function isServer()return false end
function isClient()return true end

local function actor(model)
    local player={model=model,ready=true,data={},started={},stopped={},prefix="VoiceFemale"}
    local desc={getVoicePrefix=function()return player.prefix end,setVoicePrefix=function(_,prefix)player.prefix=prefix end}
    local emitter={isPlaying=function(_,sound)return sound==player.vanillaShout end,
        stopSoundByName=function()end,stopSoundLocal=function(_,handle)player.stopped[#player.stopped+1]=handle end}
    function player:getDescriptor()return desc end
    function player:getEmitter()return emitter end
    function player:getModData()return self.data end
    function player:getPlayerNum()return 0 end
    function player:isAsleep()return false end
    function player:isDead()return self.dead end
    function player:isFemale()return true end
    function player:isSneaking()return false end
    function player:stopPlayerVoiceSound()self.vanillaShout=nil end
    function player:playSoundLocal(sound)self.started[#self.started+1]=sound;return #self.started end
    return player
end

local one,two="pmx-abe3eaaaa9d04264","pmx-40dbcd572c1fb3f8"
local localPlayer,other=actor(one),actor(one)
function getSpecificPlayer(index)if index==0 then return localPlayer end end
AimohPMXPreview={isReady=function(p)return p.ready end,modelId=function(p)return p.model end}
ISEmoteRadialMenu={onKeyReleased=function(key)
    if key==17 and localPlayer.shouting then localPlayer.vanillaShout=localPlayer.prefix.."ShoutHey" end
end,emote=function()end}
ISDPadWheels={onShout=function()end}
_G["ISUI/ISEmoteRadialMenu"]=ISEmoteRadialMenu
_G["ISUI/ISDPadWheels"]=ISDPadWheels

function verifyVoiceLifecycle()
    local a=AimohPMXCatalogVoice.install("Laiya",one,{Shout_A={duration=1000}})
    local b=AimohPMXCatalogVoice.install("AuditSoppo",two,{Shout_A={duration=1000}})
    events.OnGameStart()
    events.OnClothingUpdated(other)
    assert(a.isActive(localPlayer) and not a.isActive(other))
    assert(other.prefix=="VoiceFemale" and localPlayer.prefix=="AimohPMXLaiyaSilent")
    ISEmoteRadialMenu.onKeyReleased(17)
    assert(#localPlayer.started==0)
    localPlayer.shouting=true
    ISEmoteRadialMenu.onKeyReleased(17)
    assert(localPlayer.started[1]=="AimohPMXLaiya_Shout_A" and #other.started==0)
    events.OnSave()
    assert(localPlayer.prefix=="VoiceFemale")
    events.OnPostSave()
    assert(localPlayer.prefix=="AimohPMXLaiyaSilent")

    localPlayer.model=two
    AimohPMXVoiceEngine.modelChanged(localPlayer)
    assert(#localPlayer.stopped==1 and b.isActive(localPlayer) and not a.isActive(localPlayer))
    ISEmoteRadialMenu.onKeyReleased(17)
    assert(localPlayer.started[2]=="AimohPMXAuditSoppo_Shout_A")
    events.OnDisconnect()
    assert(#localPlayer.stopped==2 and not b.isActive(localPlayer) and localPlayer.prefix=="VoiceFemale")

    localPlayer=actor(two)
    events.OnCreatePlayer(0,localPlayer)
    assert(b.isActive(localPlayer))
    localPlayer.shouting=true
    ISEmoteRadialMenu.onKeyReleased(17)
    localPlayer.dead=true
    events.OnPlayerDeath(localPlayer)
    assert(#localPlayer.stopped==1 and not b.isActive(localPlayer) and localPlayer.prefix=="VoiceFemale")
    assert(other.prefix=="VoiceFemale" and #other.started==0)
    return "PASS production multiplayer voice routing for local shout, save, model switch, disconnect and respawn"
end
