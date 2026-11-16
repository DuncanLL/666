local callbacks = {}
local function event(name)
    return {Add = function(fn) callbacks[name] = fn end}
end
Events = {OnServerCommand=event("server"), OnTick=event("tick"),
    OnDisconnect=event("disconnect"), OnMainMenuEnter=event("menu")}
function isClient() return true end
function isServer() return false end
local actor = {data={},onlineId=4,dead=false}
function actor:getOnlineID() return self.onlineId end
function actor:getModData() return self.data end
function actor:isDead() return self.dead end
function actor:isLocalPlayer() return true end
function getSpecificPlayer(index) if index==0 then return actor end end
local peer = {}
function peer:isLocalPlayer() return false end
function peer:getUsername() return "remote" end
function getPlayerByOnlineID(id) if id==4 then return actor elseif id==5 then return peer end end
local sent = {}
function sendClientCommand(_,_,command,args)
    sent[#sent+1] = {command=command,request=args.request,appearance=args.appearance}
end

local actions, face, requestedSettings, voiceUpdates
local model="pmx-abe3eaaaa9d04264"
local clock=1000
function getTimestampMs() return clock end
function prepareExpressionEdits()
    Events = {OnTick=event("previewTick"),OnDisconnect=event("previewDisconnect"),
        OnGameStart=event("start"),OnCreatePlayer=event("create"),OnSave=event("save"),
        OnPlayerDeath=event("death"),OnFillWorldObjectContextMenu=event("context")}
    AimohPMXWindow={open=function(p,a) assert(p==actor);actions=a end,closeAll=function()end}
    function getText(key) return key end
    AimohPMXPreview.clear=function()end
    AimohPMXPreview.isReady=function() return true end
    AimohPMXPreview.modelId=function() return model end
    AimohPMXPreview.modelIdAt=function() return "another-model" end
    AimohPMXPreview.appearanceJson=function() return face end
    AimohPMXPreview.settingsJson=function() return face end
    AimohPMXPreview.networkAppearance=function() return face end
    AimohPMXPreview.enableWithSettings=function(p,id,settings)
        assert(p==actor and id==1)
        requestedSettings=settings
        return ""
    end
    voiceUpdates=0
    AimohPMXVoiceEngine={modelChanged=function(p) assert(p==actor);voiceUpdates=voiceUpdates+1 end}
    actor.data.AimohPMXPreview.profiles["another-model"]="other profile"
end

function verifyExpressionEdits()
    callbacks.start()
    callbacks.context(0,{addOption=function(_,_,p,open)open(p)end},{},false)
    callbacks.disconnect()
    face='{"expressions":{"Smile":0.45}}'
    callbacks.tick()
    callbacks.server("GFL2Exilium","Wallet",{version=1,player=6,request=3,wallet={},
        equipped={model=model,signature=string.rep("a",64),appearance=face}})
    assert(voiceUpdates==0 and actor.data.AimohPMXPreview.model~=model)
    clock=clock+250;callbacks.tick()
    assert(voiceUpdates==1 and actor.data.AimohPMXPreview.model==model)
    assert(actor.data.AimohPMXPreview.settings==face and sent[#sent].appearance==face)
    local sentBefore=#sent
    face='{"expressions":{"Smile":0.2}}';actions.expressionEdited(actor)
    clock=clock+200;callbacks.previewTick()
    assert(#sent==sentBefore)
    face='{"expressions":{"Smile":0.9}}';actions.expressionEdited(actor)
    clock=clock+249;callbacks.previewTick()
    assert(#sent==sentBefore)
    clock=clock+1;callbacks.previewTick()
    assert(#sent==sentBefore+1 and sent[#sent].appearance==face)
    assert(actor.data.AimohPMXPreview.profiles[model]==face)
    callbacks.previewTick()
    assert(#sent==sentBefore+1)
    face='{"expressions":{}}';actions.expressionEdited(actor)
    assert(actions.enable(actor,1) and requestedSettings=="other profile")
    assert(#sent==sentBefore+2 and sent[#sent].appearance==face)
    assert(actor.data.AimohPMXPreview.profiles[model]==face)
    assert(voiceUpdates==1)
    return "PASS reconnect restores local profile and voice once; expression edits save once and flush before changing outfit"
end
local applied, disabled, restoredSettings = 0, 0, nil
local signature = string.rep("a",64)
AimohPMXPreview = {
    hasModelFailure=function() return false end,
    networkSignature=function() return signature end,
    queueOwnedAppearance=function(player,model,appearance,settings)
        assert(player==actor and model=="pmx-abe3eaaaa9d04264")
        restoredSettings=settings
        return ""
    end,
    queueRemoteAppearance=function(player,model)
        assert(player==peer and model=="pmx-abe3eaaaa9d04264")
        applied=applied+1
        return ""
    end,
    disable=function(player) assert(player==peer);disabled=disabled+1 end,
}
function verifyClient(client,economy)
    local function receive(command,data)
        data.version=1
        callbacks.server("GFL2Exilium",command,data)
    end
    callbacks.tick()
    assert(sent[1].command=="Request" and not client.ready(actor))
    receive("Wallet",{player=4,request=0,wallet={targeted=10}})
    assert(client.ready(actor) and economy.balance(actor,"targeted")==10)
    local results=0
    local function onResult(result)
        assert(result.status=="unlocked")
        results=results+1
    end
    assert(client.transact(actor,"DrawCharacter","pmx-abe3eaaaa9d04264",1,onResult))
    assert(not client.transact(actor,"DrawCharacter","pmx-abe3eaaaa9d04264",1,onResult))
    clock=4100;callbacks.tick()
    assert(sent[2].request==1 and sent[3].request==1)
    local result={player=4,request=1,wallet={targeted=9},result={status="unlocked"}}
    receive("Transaction",result)
    receive("Transaction",result)
    assert(results==1 and economy.balance(actor,"targeted")==9)

    assert(client.transact(actor,"BuyOutfit","pmx-abe3eaaaa9d04264",nil,onResult))
    receive("Wallet",{player=4,request=2,wallet={targeted=9}})
    assert(client.pending(actor) and results==1)
    clock=7200;callbacks.tick()
    assert(sent[#sent].command=="BuyOutfit" and sent[#sent].request==2)
    receive("Transaction",{player=4,request=2,wallet={targeted=9},result={status="unlocked"}})
    assert(not client.pending(actor) and results==2)

    local model="pmx-abe3eaaaa9d04264"
    receive("Appearance",{player=5,owner="remote",revision=2,model=model,
        signature=string.rep("a",64),appearance="{}"})
    clock=7500;callbacks.tick()
    assert(applied==1)
    receive("Appearance",{player=5,owner="remote",revision=1,model=model,
        signature=string.rep("a",64),appearance="{}"})
    clock=7800;callbacks.tick()
    assert(applied==1)
    receive("Snapshot",{player=4,revision=3,states={}})
    assert(disabled==1)
    receive("Appearance",{player=5,owner="remote",revision=2,model=model,
        signature=string.rep("a",64),appearance="{}"})
    clock=8100;callbacks.tick()
    assert(applied==1)
    callbacks.disconnect()
    assert(not client.ready(actor) and economy.balance(actor,"targeted")==0)
    actor.data.AimohPMXPreview={model="other-model",profiles={[model]="saved settings for that outfit"}}
    callbacks.tick()
    receive("Wallet",{player=4,request=2,wallet={},equipped={model=model,signature=signature,appearance="{}"}})
    assert(restoredSettings=="saved settings for that outfit")
    actor.dead=true
    assert(not client.ready(actor))
    clock=8400;callbacks.tick()
    receive("Wallet",{player=4,request=3,wallet={targeted=25}})
    assert(economy.balance(actor,"targeted")==0)
    actor.onlineId=6;actor.dead=false
    clock=8700;callbacks.tick()
    assert(sent[#sent].command=="Request" and not client.ready(actor))
    receive("Wallet",{player=6,request=3,wallet={targeted=2}})
    assert(client.ready(actor) and economy.balance(actor,"targeted")==2)
    return "PASS client handshake, retry after wallet snapshot, stale appearance, per-model restore, disconnect and respawn"
end
