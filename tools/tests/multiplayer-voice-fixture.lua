local events={}
Events={}
for _,name in ipairs({"OnZombieDead","OnSave","OnPostSave","OnTick","OnClothingUpdated",
    "OnCreatePlayer","OnGameStart","OnMainMenuEnter","OnDisconnect","OnPlayerDeath","OnPlayerGetDamage"}) do
    local key=name
    Events[key]={Add=function(fn)events[key]=fn end}
end
GFL2VoiceEngine=nil
MoodleType=setmetatable({},{__index=function(_,key)return key end})
KeybindId={SHOUT=17}
function getCore()return {isKey=function(_,a,b)return a==b end}end
local timestamp=10000
function getTimestampMs()return timestamp end
function isServer()return false end
function isClient()return true end
local language="CN"
local screenWidth=1280
Translator={getLanguage=function()return {name=function()return language end}end}
function getTextOrNull(key)
    assert(key=="IGUI_GFL2_Subtitle_GFL2PmxAuditSoppo_Shout_A")
    return ({EN="English caption",KO="Korean caption"})[language]
end
UIFont={Small="small"}
UIManager={getSpeedControls=function()end}
function getPlayerScreenWidth(index) assert(index==0);return screenWidth end
function getTextManager() return {MeasureStringX=function(_,_,value)return #value*10 end} end

local function actor(model)
    local player={model=model,ready=true,data={},started={},stopped={},prefix="VoiceFemale"}
    local desc={getVoicePrefix=function()return player.prefix end,setVoicePrefix=function(_,prefix)player.prefix=prefix end}
    local emitter={isPlaying=function(_,sound)return type(sound)=="number" or sound==player.vanillaShout end,
        stopSoundByName=function()end,stopSoundLocal=function(_,handle)player.stopped[#player.stopped+1]=handle end}
    function player:getDescriptor()return desc end
    function player:getEmitter()return emitter end
    function player:getModData()return self.data end
    function player:getMoodles()return {getMoodleLevel=function()return 0 end}end
    function player:getPlayerNum()return 0 end
    function player:isAsleep()return false end
    function player:isDead()return self.dead end
    function player:isFemale()return true end
    function player:isSneaking()return false end
    function player:stopPlayerVoiceSound()self.vanillaShout=nil end
    function player:playSoundLocal(sound)self.started[#self.started+1]=sound;return #self.started end
    function player:getSayLine()return self.caption end
    function player:SayDebug(_,text)self.caption=text end
    function player:addLineChatElement(text,_,_,_,font)
        assert(font==UIFont.Small)
        self.caption=text
        self.captionAdds=(self.captionAdds or 0)+1
    end
    return player
end

local one,two="pmx-abe3eaaaa9d04264","pmx-40dbcd572c1fb3f8"
local localPlayer,other=actor(one),actor(one)
function getSpecificPlayer(index)if index==0 then return localPlayer end end
GFL2CharacterRuntime={isReady=function(p)return p.ready end,modelId=function(p)return p.model end}
ISEmoteRadialMenu={onKeyReleased=function(key)
    if key==17 and localPlayer.shouting then localPlayer.vanillaShout=localPlayer.prefix.."ShoutHey" end
end,emote=function()end}
ISDPadWheels={onShout=function()end}
_G["ISUI/ISEmoteRadialMenu"]=ISEmoteRadialMenu
_G["ISUI/ISDPadWheels"]=ISDPadWheels

function verifyVoiceLifecycle()
    local previousFamily=string.char(65,105,109,111,104).."PMX"
    local previousKey=previousFamily.."LaiyaOriginalVoicePrefix"
    localPlayer.data[previousKey]="VoiceFemale"
    localPlayer.prefix=previousFamily.."LaiyaSilent"
    GFL2VoiceRouting.restoreRetired(localPlayer)
    assert(localPlayer.prefix=="VoiceFemale" and localPlayer.data[previousKey]==nil)
    local a=GFL2CatalogVoice.install("Laiya",one,{Shout_A={duration=1000,chinese="Chinese caption",japanese="Japanese caption"}})
    local b=GFL2CatalogVoice.install("AuditSoppo",two,{Shout_A={duration=1000,chinese="Chinese caption",japanese="Japanese caption"}})
    events.OnGameStart()
    events.OnClothingUpdated(other)
    assert(a.isActive(localPlayer) and not a.isActive(other))
    assert(other.prefix=="VoiceFemale" and localPlayer.prefix=="GFL2PmxLaiyaSilent")
    ISEmoteRadialMenu.onKeyReleased(17)
    assert(#localPlayer.started==0)
    localPlayer.shouting=true
    ISEmoteRadialMenu.onKeyReleased(17)
    assert(localPlayer.started[1]=="GFL2PmxLaiya_Shout_A" and #other.started==0)
    assert(localPlayer.caption=="Chinese caption")
    events.OnSave()
    assert(localPlayer.prefix=="VoiceFemale")
    events.OnPostSave()
    assert(localPlayer.prefix=="GFL2PmxLaiyaSilent")

    language="JP"
    localPlayer.model=two
    GFL2VoiceEngine.modelChanged(localPlayer)
    assert(#localPlayer.stopped==1 and b.isActive(localPlayer) and not a.isActive(localPlayer))
    ISEmoteRadialMenu.onKeyReleased(17)
    assert(localPlayer.started[2]=="GFL2PmxAuditSoppo_Shout_A")
    assert(localPlayer.caption=="Japanese caption")
    events.OnDisconnect()
    assert(#localPlayer.stopped==2 and not b.isActive(localPlayer) and localPlayer.prefix=="VoiceFemale")

    language="EN"
    localPlayer=actor(two)
    events.OnCreatePlayer(0,localPlayer)
    localPlayer.shouting=true
    ISEmoteRadialMenu.onKeyReleased(17)
    assert(localPlayer.caption=="English caption")
    events.OnDisconnect()

    language="KO"
    screenWidth=112
    localPlayer=actor(two)
    events.OnCreatePlayer(0,localPlayer)
    assert(b.isActive(localPlayer))
    localPlayer.shouting=true
    ISEmoteRadialMenu.onKeyReleased(17)
    assert(localPlayer.caption=="Korean\ncaption")
    localPlayer.caption="interrupted"
    timestamp=11000
    b.tick()
    assert(localPlayer.caption=="Korean\ncaption" and localPlayer.captionAdds==2)
    timestamp=12000
    b.tick()
    assert(localPlayer.caption=="Korean\ncaption" and localPlayer.captionAdds==2)
    localPlayer.dead=true
    events.OnPlayerDeath(localPlayer)
    assert(#localPlayer.stopped==1 and not b.isActive(localPlayer) and localPlayer.prefix=="VoiceFemale")
    assert(other.prefix=="VoiceFemale" and #other.started==0)
    return "PASS production multiplayer voice routing and selected CN/EN/JP/KO captions"
end
