local E={cost={targeted=150,outfit=120},creditsPerCash=60}
E.keys={"targeted","outfit","credits"}
local snapshots=setmetatable({},{__mode="k"})

local function wallet(player)
    if E.serverWallet then return E.serverWallet(player) end
    if isClient and isClient() then return snapshots[player] or {} end
    local data=player:getModData()
    local room=data.R116ReadyRoomWallet
    if type(room)~="table" then
        room={}
        data.R116ReadyRoomWallet=room
    end
    return room
end

function E.balance(player,key)
    local n=wallet(player)[key]
    return type(n)=="number" and n>=0 and n<9007199254740992 and math.floor(n) or 0
end

local function cash(player)
    return player:getInventory():getAllTypeRecurse("Base.Money")
end

function E.available(player,key)
    if key=="credits" then return cash(player):size() end
    local cost=E.cost[key]
    if cost then
        return math.floor(E.balance(player,"credits")/cost)
    end
    return 0
end

function E.buy(player,key,count)
    if isClient and isClient() and not E.serverWallet then return false end
    if type(count)~="number" or count<1 or count%1~=0 then return false end
    local room=wallet(player)
    if key=="credits" then
        local items=cash(player)
        if count>items:size() then return false end
        local notes={}
        local total=E.balance(player,"credits")+count*E.creditsPerCash
        if total>=9007199254740992 then return false end
        for i=0,count-1 do
            local note=items:get(i)
            local container=note:getContainer()
            if not container or note:getFullType()~="Base.Money" then return false end
            notes[#notes+1]={note,container}
        end
        for _,row in ipairs(notes)do
            local note,container=row[1],row[2]
            player:removeFromHands(note)
            sendRemoveItemFromContainer(container,note)
            container:Remove(note)
        end
        room.credits=total
        return true
    end
    local unitCost=E.cost[key]
    if not unitCost then return false end
    local balance=E.balance(player,"credits")
    local cost=count*unitCost
    if cost>balance then return false end
    local total=E.balance(player,key)+count
    if total>=9007199254740992 then return false end
    room.credits=balance-cost
    room[key]=total
    return true
end

E.characterGuarantee=80
E.outfitGuarantee=100
E.defaultOutfitPrice=1080

function E.resetTestDraws(player,outfitPools)
    if isClient and isClient() then return end
    local room=wallet(player)
    if room.drawReset20261113 then return end
    room.drawResetBackup20261113={
        recruitment=room.recruitment,outfits=room.outfits,
        daiyanUnlocked=room.daiyanUnlocked,daiyanMisses=room.daiyanMisses,
        credits=room.credits,targeted=room.targeted,outfit=room.outfit,
    }
    local purchased={}
    for id,value in pairs(room.outfits or {}) do
        local pool=outfitPools[id]
        if not pool or (pool.preservePriorPurchase and value==true) then purchased[id]=value end
    end
    room.recruitment={owned={},misses=0}
    room.outfits=purchased
    room.daiyanUnlocked=nil
    room.daiyanMisses=nil
    room.credits=0
    room.targeted=0
    room.outfit=0
    room.drawReset20261113=true
end

local function recruitment(player)
    local room=wallet(player)
    if type(room.recruitment)~="table" then
        local owned={}
        if room.daiyanUnlocked==true then owned.Daiyan=true end
        room.recruitment={owned=owned,misses=E.balance(player,"daiyanMisses")}
    end
    return room.recruitment
end

function E.characterChance(nextPull)
    if nextPull>=E.characterGuarantee then return 10000 end
    return 60+math.max(0,nextPull-58)*450
end

function E.characterUnlocked(player,key)
    return recruitment(player).owned[key]==true
end

function E.characterMisses(player)
    local n=recruitment(player).misses
    return type(n)=="number" and n>=0 and math.min(E.characterGuarantee-1,math.floor(n)) or 0
end

local function draw(count,tickets,misses,guarantee,chance,roll)
    if count~=1 and count~=10 then return "unavailable" end
    local maximum=math.min(count,guarantee-misses)
    if tickets<maximum then return "short" end
    local rng=roll or ZombRand
    local results={}
    for spent=1,maximum do
        misses=misses+1
        local hit=misses==guarantee or rng(10000)<chance(misses)
        results[spent]=hit
        if hit then return "unlocked",results,0 end
    end
    return "miss",results,misses
end

function E.drawCharacter(player,key,count,roll)
    if isClient and isClient() and not E.serverWallet then return "unavailable" end
    if type(key)~="string" or key=="" or E.characterUnlocked(player,key) then return "unavailable" end
    local tickets=E.balance(player,"targeted")
    local status,results,misses=draw(count,tickets,E.characterMisses(player),E.characterGuarantee,E.characterChance,roll)
    if results then
        wallet(player).targeted=tickets-#results
        local progress=recruitment(player)
        progress.misses=misses
        if status=="unlocked" then progress.owned[key]=true end
    end
    return status,results
end

local function outfits(player)
    local room=wallet(player)
    if type(room.outfits)~="table" then room.outfits={} end
    return room.outfits
end

function E.outfitOwned(player,id)
    return outfits(player)[id]==true
end

function E.outfitMisses(player,id)
    local n=outfits(player)[id]
    return type(n)=="number" and n>=0 and math.min(E.outfitGuarantee-1,math.floor(n)) or 0
end

function E.buyOutfit(player,id,price)
    if isClient and isClient() and not E.serverWallet then return "unavailable" end
    if type(price)~="number" or price<=0 or price%1~=0 or E.outfitOwned(player,id) then return "unavailable" end
    local credits=E.balance(player,"credits")
    if credits<price then return "short" end
    wallet(player).credits=credits-price
    outfits(player)[id]=true
    return "unlocked"
end

function E.outfitChance(nextPull)
    if nextPull>=E.outfitGuarantee then return 10000 end
    -- R116 tuning in basis points, using the original disclosure's stage boundaries.
    return 10+math.max(0,math.min(nextPull,49)-25)*2
        +math.max(0,math.min(nextPull,69)-49)*4
        +math.max(0,math.min(nextPull,79)-69)*7
        +math.max(0,nextPull-79)*12
end

function E.drawOutfit(player,id,count,roll)
    if isClient and isClient() and not E.serverWallet then return "unavailable" end
    if E.outfitOwned(player,id) then return "unavailable" end
    local tickets=E.balance(player,"outfit")
    local status,results,misses=draw(count,tickets,E.outfitMisses(player,id),E.outfitGuarantee,E.outfitChance,roll)
    if results then
        wallet(player).outfit=tickets-#results
        outfits(player)[id]=status=="unlocked" or misses
    end
    return status,results
end

-- The UI reads its last server snapshot through the same wallet functions.
function E.setSnapshot(player,snapshot)
    snapshots[player]=snapshot
end

return E
