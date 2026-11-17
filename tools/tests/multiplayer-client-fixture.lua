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
function peer:isDead() return self.dead==true end
function getPlayerByOnlineID(id) if id==4 then return actor elseif id==5 then return peer end end
local sent = {}
function sendClientCommand(_,_,command,args)
    sent[#sent+1] = {command=command,request=args.request,appearance=args.appearance,attempt=args.attempt,
        handshake=args.handshake,session=args.session}
end

local actions, face, requestedSettings, voiceUpdates
local model="pmx-abe3eaaaa9d04264"
local renderedModel,renderedReady,queuedModel
local clock=1000
function getTimestampMs() return clock end
function prepareExpressionEdits()
    Events = {OnTick=event("previewTick"),OnDisconnect=event("previewDisconnect"),
        OnGameStart=event("start"),OnCreatePlayer=event("create"),OnSave=event("save"),
        OnPlayerDeath=event("death"),OnFillWorldObjectContextMenu=event("context")}
    GFL2RoomWindow={open=function(p,a) assert(p==actor);actions=a end,closeAll=function()end}
    function getText(key) return key end
    GFL2CharacterRuntime.clear=function()end
    renderedModel,renderedReady=model,true
    GFL2CharacterRuntime.isReady=function() return renderedReady end
    GFL2CharacterRuntime.modelId=function() return renderedModel end
    GFL2CharacterRuntime.modelIdAt=function() return "pmx-40dbcd572c1fb3f8" end
    GFL2CharacterRuntime.disable=function(p)
        assert(p==actor)
        renderedModel,renderedReady="",false
    end
    GFL2CharacterRuntime.queueOwnedAppearance=function(p,id,appearance,settings)
        assert(p==actor and id==model)
        queuedModel=id;requestedSettings=settings
        return ""
    end
    GFL2CharacterRuntime.appearanceJson=function() return face end
    GFL2CharacterRuntime.settingsJson=function() return face end
    GFL2CharacterRuntime.networkAppearance=function() return face end
    GFL2CharacterRuntime.enableWithSettings=function(p,id,settings)
        assert(p==actor and id==1)
        requestedSettings=settings
        renderedReady=false
        return ""
    end
    voiceUpdates=0
    GFL2VoiceEngine={modelChanged=function(p) assert(p==actor);voiceUpdates=voiceUpdates+1 end}
    actor.data.GFL2CharacterRuntime.profiles["pmx-40dbcd572c1fb3f8"]="other profile"
end

function verifyExpressionEdits()
    callbacks.start()
    callbacks.context(0,{addOption=function(_,_,p,open)open(p)end},{},false)
    callbacks.disconnect()
    face='{"expressions":{"Smile":0.45}}'
    callbacks.tick()
    callbacks.server("GFL2Exilium","Wallet",{version=1,player=6,request=3,wallet={},
        equipped={model=model,signature=string.rep("a",64),appearance=face}})
    assert(voiceUpdates==0 and actor.data.GFL2CharacterRuntime.model~=model)
    clock=clock+250;callbacks.tick()
    assert(voiceUpdates==1 and actor.data.GFL2CharacterRuntime.model==model)
    assert(actor.data.GFL2CharacterRuntime.settings==face and sent[#sent].appearance==face)
    local sentBefore=#sent
    face='{"expressions":{"Smile":0.2}}';actions.expressionEdited(actor)
    clock=clock+200;callbacks.previewTick()
    assert(#sent==sentBefore)
    face='{"expressions":{"Smile":0.9}}';actions.expressionEdited(actor)
    clock=clock+249;callbacks.previewTick()
    assert(#sent==sentBefore)
    clock=clock+1;callbacks.previewTick()
    assert(#sent==sentBefore+1 and sent[#sent].appearance==face)
    assert(actor.data.GFL2CharacterRuntime.profiles[model]==face)
    callbacks.previewTick()
    assert(#sent==sentBefore+1)
    face='{"expressions":{}}';actions.expressionEdited(actor)
    assert(actions.enable(actor,1) and requestedSettings=="other profile")
    assert(#sent==sentBefore+2 and sent[#sent].appearance==face)
    assert(actor.data.GFL2CharacterRuntime.profiles[model]==face)
    assert(voiceUpdates==1)
    return "PASS reconnect restores local profile and voice once; expression edits save once and flush before changing outfit"
end

function verifyAppearanceRejections()
    local function reject(attempt,equipped)
        callbacks.server("GFL2Exilium","AppearanceRejected",{version=1,player=6,attempt=attempt,equipped=equipped})
    end
    local older=sent[#sent].attempt or 7
    reject(older)
    assert(renderedModel==model and voiceUpdates==1,"an old rejection interrupted a new model load")
    renderedModel,renderedReady="pmx-40dbcd572c1fb3f8",true
    callbacks.previewTick()
    assert(older~=sent[#sent].attempt and actor.data.GFL2CharacterRuntime.model==renderedModel)
    local current=sent[#sent].attempt
    reject(older)
    clock=clock+250;callbacks.tick()
    assert(renderedModel=="pmx-40dbcd572c1fb3f8" and voiceUpdates==2,"the last authorized model was cleared")
    local sentBefore=#sent
    queuedModel=nil
    local savedProfile=actor.data.GFL2CharacterRuntime.profiles[model]
    reject(current,{model=model,signature=string.rep("a",64),appearance=face})
    assert(renderedModel=="" and voiceUpdates==3 and #sent==sentBefore)
    assert(actor.data.GFL2CharacterRuntime.model==nil and actor.data.GFL2CharacterRuntime.profiles[model]==savedProfile)
    assert(queuedModel==model and requestedSettings==savedProfile)
    renderedModel,renderedReady=queuedModel,true
    clock=clock+250;callbacks.tick()
    assert(actor.data.GFL2CharacterRuntime.model==model and voiceUpdates==4 and #sent==sentBefore+1)
    reject(sent[#sent].attempt)
    assert(renderedModel=="" and voiceUpdates==5 and #sent==sentBefore+1)
    assert(actor.data.GFL2CharacterRuntime.model==nil and actor.data.GFL2CharacterRuntime.profiles[model]==face)
    return "PASS late appearance rejection leaves the newer model intact, current rejection restores the approved model and its voice"
end
local applied, disabled, cleared, restoredSettings = 0, 0, 0, nil
local signature = string.rep("a",64)
GFL2CharacterRuntime = {
    hasModelFailure=function() return false end,
    networkSignature=function() return signature end,
    queueOwnedAppearance=function(player,model,appearance,settings)
        assert(player==actor and model=="pmx-abe3eaaaa9d04264")
        restoredSettings=settings
        return ""
    end,
    queueRemoteAppearance=function(player,model)
        assert(player==peer)
        if model=="" then cleared=cleared+1
        else assert(model=="pmx-abe3eaaaa9d04264");applied=applied+1 end
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
    receive("Appearance",{player=5,owner="remote",revision=4,model=model,signature=signature,appearance="{}",session=24})
    clock=8400;callbacks.tick()
    assert(applied==2)
    peer.dead=true
    clock=8700;callbacks.tick()
    assert(disabled==2)
    receive("Appearance",{player=5,owner="remote",revision=5,model=model,signature=signature,appearance="{}",session=24})
    peer=setmetatable({}, {__index={isLocalPlayer=peer.isLocalPlayer,getUsername=peer.getUsername,isDead=peer.isDead}})
    clock=9000;callbacks.tick()
    assert(applied==2,"the new remote survivor inherited the dead actor's appearance")
    receive("Appearance",{player=5,owner="remote",revision=6,model=model,signature=signature,appearance="{}",session=25})
    clock=9300;callbacks.tick()
    assert(applied==3)
    receive("Appearance",{player=5,owner="remote",revision=7,model=model,signature=signature,appearance="{}",session=26})
    clock=9600;callbacks.tick()
    assert(applied==3 and disabled==3,"new appearance was applied to the previous life before the PZ player arrived")
    peer=setmetatable({}, {__index={isLocalPlayer=peer.isLocalPlayer,getUsername=peer.getUsername,isDead=peer.isDead}})
    clock=9900;callbacks.tick()
    assert(applied==4)
    receive("Appearance",{player=5,owner="remote",revision=8,model="",signature="",appearance="",session=26})
    clock=10200;callbacks.tick()
    assert(applied==4 and cleared==1)
    receive("Appearance",{player=5,owner="remote",revision=9,model=model,signature=signature,appearance="{}",session=26})
    clock=10500;callbacks.tick()
    assert(applied==5,"the same live player could not re-enable an appearance")
    callbacks.disconnect()
    assert(not client.ready(actor) and economy.balance(actor,"targeted")==0)
    actor.data.GFL2CharacterRuntime={model="other-model",profiles={[model]="saved settings for that outfit"}}
    callbacks.tick()
    receive("Wallet",{player=4,request=2,wallet={},equipped={model=model,signature=signature,appearance="{}"}})
    assert(restoredSettings=="saved settings for that outfit")
    actor.dead=true
    assert(not client.ready(actor))
    clock=10800;callbacks.tick()
    receive("Wallet",{player=4,request=3,wallet={targeted=25}})
    assert(economy.balance(actor,"targeted")==0)
    actor.onlineId=6;actor.dead=false
    local oldHandshake=sent[#sent].handshake
    clock=11100;callbacks.tick()
    assert(sent[#sent].command=="Request" and not client.ready(actor))
    local newHandshake=sent[#sent].handshake
    assert(oldHandshake and newHandshake and oldHandshake~=newHandshake)
    receive("Wallet",{player=6,request=9,wallet={targeted=25},handshake=oldHandshake,session=34})
    assert(not client.ready(actor) and economy.balance(actor,"targeted")==0)
    receive("Wallet",{player=6,request=3,wallet={targeted=2},handshake=newHandshake,session=35})
    assert(client.ready(actor) and economy.balance(actor,"targeted")==2)
    assert(client.transact(actor,"DrawCharacter",model,1,onResult) and sent[#sent].session==35)
    receive("Transaction",{player=6,request=4,wallet={targeted=25},session=34,result={status="unlocked"}})
    assert(client.pending(actor) and economy.balance(actor,"targeted")==2 and results==2)
    receive("Transaction",{player=6,request=4,wallet={targeted=1},session=35,result={status="unlocked"}})
    assert(not client.pending(actor) and economy.balance(actor,"targeted")==1 and results==3)
    return "PASS client handshake, retry after wallet snapshot, stale appearance, per-model restore, disconnect and respawn"
end
