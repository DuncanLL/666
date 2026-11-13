local callbacks = {}
local function event(name)
    return {Add = function(fn) callbacks[name] = fn end}
end
Events = {OnServerCommand=event("server"), OnTick=event("tick"),
    OnDisconnect=event("disconnect"), OnMainMenuEnter=event("menu")}
function isClient() return true end
function isServer() return false end
local actor = {data={}}
function actor:getOnlineID() return 4 end
function actor:getModData() return self.data end
function actor:isDead() return false end
function actor:isLocalPlayer() return true end
function getSpecificPlayer(index) if index==0 then return actor end end
local peer = {}
function peer:isLocalPlayer() return false end
function peer:getUsername() return "remote" end
function getPlayerByOnlineID(id) if id==4 then return actor elseif id==5 then return peer end end
local sent = {}
function sendClientCommand(_,_,command,args)
    sent[#sent+1] = {command=command,request=args.request}
end
local applied, disabled = 0, 0
AimohPMXPreview = {
    hasModelFailure=function() return false end,
    queueRemoteAppearance=function(player,model)
        assert(player==peer and model=="pmx-abe3eaaaa9d04264")
        applied=applied+1
        return ""
    end,
    disable=function(player) assert(player==peer);disabled=disabled+1 end,
}
local clock=1000
function getTimestampMs() return clock end
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
    local model="pmx-abe3eaaaa9d04264"
    receive("Appearance",{player=5,owner="remote",revision=2,model=model,
        signature=string.rep("a",64),appearance="{}"})
    clock=4400;callbacks.tick()
    assert(applied==1)
    receive("Appearance",{player=5,owner="remote",revision=1,model=model,
        signature=string.rep("a",64),appearance="{}"})
    clock=4700;callbacks.tick()
    assert(applied==1)
    receive("Snapshot",{player=4,revision=3,states={}})
    assert(disabled==1)
    receive("Appearance",{player=5,owner="remote",revision=2,model=model,
        signature=string.rep("a",64),appearance="{}"})
    clock=5000;callbacks.tick()
    assert(applied==1)
    callbacks.disconnect()
    assert(not client.ready(actor) and economy.balance(actor,"targeted")==0)
    return "PASS client handshake, same-request retry, duplicate response, stale appearance and disconnect"
end
