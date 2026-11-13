local luaEnvironment = {}
local function event()
    return {Add = function(fn) luaEnvironment[#luaEnvironment+1] = fn end}
end
Events = {OnClientCommand=event(), OnPlayerDeath={Add=function() end}}
function isServer() return true end
function isClient() return false end
local saves = {}
ModData = {getOrCreate=function() return saves end}
local enabled = true
function getActivatedMods() return {contains=function(_,mod) return enabled and mod=="GFL2Laiya" end} end
local player = {
    getUsername=function() return "player-a" end,
    getOnlineID=function() return 4 end,
}
local playerB = {
    getUsername=function() return "player-b" end,
    getOnlineID=function() return 5 end,
}
function getPlayerByOnlineID(id) return id==4 and player or id==5 and playerB or nil end
local replies = {}
function sendServerCommand(first,second,third,fourth)
    if type(first)=="table" then replies[third]=fourth
    else replies[second]=third end
end
local rolls = 0
function ZombRand() rolls=rolls+1; return 0 end

function verifyServer()
    local receive=luaEnvironment[1]
    assert(receive)
    local function request(owner,command,args)
        args.version=1
        receive("GFL2Exilium",command,owner,args)
    end
    local model="pmx-abe3eaaaa9d04264"
    local appearance={model=model,signature=string.rep("a",64),appearance='{"choices":{},"material_visibility":[]}'}
    request(player,"Request",{})
    assert(replies.Wallet.request==0 and replies.Wallet.equipped==nil)
    saves["player-a"].wallet.targeted=10
    request(player,"Appearance",appearance)
    assert(replies.AppearanceRejected and replies.Appearance==nil)
    request(player,"DrawCharacter",{target=model,count=10,request=1})
    assert(replies.Transaction.result.status=="unlocked")
    assert(saves["player-a"].wallet.targeted==9 and rolls==1)
    request(player,"DrawCharacter",{target=model,count=10,request=1})
    assert(replies.Transaction.result.status=="unlocked" and rolls==1)
    assert(saves["player-a"].wallet.targeted==9)
    request(player,"Wear",{target=model,request=2})
    assert(replies.Transaction.result.status=="ok")
    request(player,"Appearance",appearance)
    assert(replies.Appearance.player==4 and replies.Appearance.model==model)
    request(playerB,"Request",{})
    assert(replies.Wallet.player==5 and replies.Wallet.equipped==nil and replies.Wallet.request==0)
    assert(#replies.Snapshot.states==1 and replies.Snapshot.states[1].player==4)
    request(player,"Request",{})
    assert(replies.Wallet.equipped.model==model)
    enabled=false
    request(player,"Wear",{target=model,request=3})
    assert(replies.Transaction.result.status=="unavailable")
    request(player,"Request",{})
    assert(replies.Wallet.equipped==nil)
    return "PASS server ownership, replay, separate wallets, active pack and restore"
end
