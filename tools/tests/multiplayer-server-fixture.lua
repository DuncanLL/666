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
function getActivatedMods()
    return {contains=function(_,mod) return enabled and (mod=="GFL2Laiya" or mod=="GFL2Soppo") end}
end
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

local weapon, magazine = "GFL2.Skyhowl", "GFL2.SkyhowlMagazine"
local items, syncAdded, syncRemoved, syncChanged = {}, {}, {}, {}
local purse = {}
local inventory = {}
local function item(id, container)
    local data = {}
    return {getFullType=function()return id end, getContainer=function()return container end,
        getModData=function()return data end, setContainsClip=function(self,value)self.clip=value end}
end
function inventory:containsTypeRecurse(id)
    return #self:getAllTypeRecurse(id).rows>0
end
function inventory:getAllTypeRecurse(id)
    local source, result = id=="Base.Money" and purse or items, {}
    for _,value in ipairs(source) do if value:getFullType()==id then result[#result+1]=value end end
    return {rows=result,size=function()return #result end,get=function(_,index)return result[index+1] end}
end
function inventory:AddItem(id)
    local added=item(id,self)
    items[#items+1]=added
    return added
end
function inventory:Remove(itemToRemove)
    for index, value in ipairs(purse) do
        if value==itemToRemove then table.remove(purse,index);return end
    end
    error("Unexpected removal")
end
for _=1,3 do purse[#purse+1]=item("Base.Money",inventory) end
function playerB:getInventory()return inventory end
function playerB:removeFromHands(value)assert(value:getContainer()==inventory) end
function playerB:resetEquippedHandsModels() end
function sendAddItemToContainer(container,value)
    assert(container==inventory);syncAdded[#syncAdded+1]=value
end
function sendRemoveItemFromContainer(container,value)
    assert(container==inventory);syncRemoved[#syncRemoved+1]=value
end
function syncItemModData(owner,value)
    assert(owner==playerB);syncChanged[#syncChanged+1]=value
end
function getScriptManager()
    return {FindItem=function(_,id) return id==magazine and {} end,
        getModelScript=function(_,name)
            return (name==weapon.."Skin4332565" or name==weapon.."Skin4332565Empty") and {}
        end}
end

function verifyServer()
    local receive=luaEnvironment[1]
    assert(receive)
    local function request(owner,command,args)
        args.version=1
        receive("GFL2Exilium",command,owner,args)
    end
    local model="pmx-abe3eaaaa9d04264"
    local appearance={model=model,signature=string.rep("a",64),appearance='{"choices":{},"material_visibility":[],"expressions":{"Smile":0.6}}'}
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
    assert(replies.Snapshot.states[1].appearance==appearance.appearance)
    request(player,"Request",{})
    assert(replies.Wallet.equipped.model==model and replies.Wallet.equipped.appearance==appearance.appearance)
    local soppo, outfit = "pmx-40dbcd572c1fb3f8", "pmx-7d46f9b7159f3332"
    saves["player-b"].wallet.targeted=1
    saves["player-b"].wallet.credits=1080
    request(playerB,"DrawCharacter",{target=soppo,count=1,request=1})
    assert(replies.Transaction.result.status=="unlocked")
    request(playerB,"Wear",{target=soppo,request=2})
    assert(replies.Transaction.result.status=="ok")
    request(playerB,"Wear",{target=outfit,request=3})
    assert(replies.Transaction.result.status=="unavailable")
    request(playerB,"BuyOutfit",{target=outfit,request=4})
    assert(replies.Transaction.result.status=="unlocked" and saves["player-b"].wallet.credits==0)
    request(playerB,"Wear",{target=outfit,request=5})
    assert(replies.Transaction.result.status=="ok")

    request(playerB,"Exchange",{target="credits",count=3,request=6})
    assert(replies.Transaction.result.status=="ok" and #syncRemoved==3 and #purse==0)
    request(playerB,"Exchange",{target="credits",count=3,request=6})
    assert(saves["player-b"].wallet.credits==180 and #syncRemoved==3)
    request(playerB,"Exchange",{target="targeted",count=1,request=7})
    assert(replies.Transaction.result.status=="ok" and saves["player-b"].wallet.credits==30)
    request(playerB,"ClaimMagazine",{target=weapon,request=8})
    assert(replies.Transaction.result.status=="unavailable" and #syncAdded==0)
    request(playerB,"DrawWeapon",{target=weapon,count=1,request=9})
    assert(replies.Transaction.result.status=="unlocked")
    request(playerB,"ClaimWeapon",{target=weapon,request=10})
    assert(replies.Transaction.result.status=="ok" and #syncAdded==1 and syncAdded[1].clip)
    request(playerB,"ClaimWeapon",{target=weapon,request=10})
    assert(replies.Transaction.result.status=="ok" and #syncAdded==1)
    request(playerB,"ClaimMagazine",{target=weapon,request=11})
    assert(replies.Transaction.result.status=="ok" and #syncAdded==2 and syncAdded[2]:getFullType()==magazine)
    request(playerB,"WeaponSkin",{target=weapon..":4332565",request=12})
    assert(replies.Transaction.result.status=="ok" and #syncChanged==1)
    assert(syncAdded[1]:getModData().GFL2WeaponSkin==weapon.."Skin4332565")
    request(playerB,"WeaponSkin",{target=weapon..":",request=13})
    assert(replies.Transaction.result.status=="ok" and #syncChanged==2)
    assert(syncAdded[1]:getModData().GFL2WeaponSkin==nil)
    enabled=false
    request(player,"Wear",{target=model,request=3})
    assert(replies.Transaction.result.status=="unavailable")
    request(player,"Request",{})
    assert(replies.Wallet.equipped==nil)
    return "PASS server ownership, replay, separate wallets, Soppo, outfits, cash, tickets, signature weapon, magazine, skin and restore"
end
