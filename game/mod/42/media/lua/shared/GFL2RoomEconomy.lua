local weaponSkins=require "GFL2WeaponSkins"
local E={cost={targeted=150,outfit=120},creditsPerCash=60}
E.keys={"targeted","outfit","credits"}
local snapshots=setmetatable({},{__mode="k"})
local recordingUnlocks=setmetatable({},{__mode="k"})

local walletKey = "GFL2ReadyRoomWallet"
local settingsKeys = {"GFL2CharacterRuntime", "GFL2Expressions", "GFL2GripCalibration", "GFL2GripCalibrationAim"}

-- Length and unsigned 31-based fingerprint of each retired save key.
local retiredSaveKeys = {
    [3750646163] = {19, walletKey},
    [3398986123] = {15, settingsKeys[1]},
    [3849825566] = {19, settingsKeys[2]},
    [834440086] = {24, settingsKeys[3]},
    [3828925903] = {27, settingsKeys[4]},
}

local retiredRecruitmentKeys = {
    [998278171] = {10, "GFL2Qiuhua"},
    [3775001711] = {9, "GFL2Soppo"},
    [2545452340] = {11, "GFL2Sextans"},
    [3780534798] = {9, "GFL2Yohee"},
    [4168846905] = {12, "GFL2Macqiato"},
    [3558508035] = {13, "GFL2Destroyer"},
    [1137204967] = {10, "GFL2Vector"},
    [2656908111] = {12, "GFL2Phaetusa"},
    [3768237156] = {9, "GFL2Lenna"},
}
local upgradedOwnership = setmetatable({}, {__mode="k"})

local function upgradeSavedKeys(data, replacements, valueType)
    replacements = replacements or retiredSaveKeys
    valueType = valueType or "table"
    local changes = {}
    for key, value in pairs(data) do
        if type(key) == "string" and type(value) == valueType then
            local fingerprint = 0
            for index = 1, #key do
                fingerprint = (fingerprint * 31 + string.byte(key, index)) % 4294967296
            end
            local retired = replacements[fingerprint]
            if retired and retired[1] == #key then changes[key] = retired[2] end
        end
    end
    for key, replacement in pairs(changes) do
        if data[replacement] == nil then data[replacement] = data[key] end
        data[key] = nil
    end
end

local function worldSlot(player)
    local slots = ModData.getOrCreate("GFL2ExiliumReadyRoomV1")
    local id = tostring(player:getPlayerNum())
    if not slots[id] then slots[id] = {} end
    return slots[id]
end

local function wallet(player)
    if E.serverWallet then return E.serverWallet(player) end
    if isClient and isClient() then return snapshots[player] or {} end
    local data = player:getModData()
    local slot = worldSlot(player)
    if not slot.wallet then
        if not data[walletKey] then upgradeSavedKeys(data) end
        slot.wallet = type(data[walletKey]) == "table" and data[walletKey] or {}
    end
    data[walletKey] = slot.wallet
    return slot.wallet
end

function E.restoreLocal(player)
    local data = player:getModData()
    upgradeSavedKeys(data)
    if (isClient and isClient()) or E.serverWallet then return end
    wallet(player)
    local slot = worldSlot(player)
    if not slot.settings then
        slot.settings = {}
        for _, key in ipairs(settingsKeys) do slot.settings[key] = data[key] end
    else
        upgradeSavedKeys(slot.settings)
        for _, key in ipairs(settingsKeys) do data[key] = slot.settings[key] end
    end
end

function E.saveLocal(player)
    if (isClient and isClient()) or E.serverWallet then return end
    wallet(player)
    local slot = worldSlot(player)
    local data = player:getModData()
    upgradeSavedKeys(data)
    slot.settings = slot.settings or {}
    upgradeSavedKeys(slot.settings)
    for _, key in ipairs(settingsKeys) do slot.settings[key] = data[key] end
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
E.weaponGuarantee=70
E.outfitGuarantee=100
E.defaultOutfitPrice=1080

local function recruitment(player)
    local room=wallet(player)
    if type(room.recruitment)~="table" then
        local owned={}
        if room.daiyanUnlocked==true then owned.Daiyan=true end
        room.recruitment={owned=owned,misses=E.balance(player,"daiyanMisses")}
    end
    local owned=room.recruitment.owned
    if not upgradedOwnership[owned] then
        upgradeSavedKeys(owned, retiredRecruitmentKeys, "boolean")
        upgradedOwnership[owned]=true
    end
    return room.recruitment
end

function E.characterChance(nextPull)
    if nextPull>=E.characterGuarantee then return 10000 end
    return 60+math.max(0,nextPull-58)*450
end

function E.characterUnlocked(player,key)
    return recordingUnlocks[player]~=nil or recruitment(player).owned[key]==true
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
    return recordingUnlocks[player]~=nil or outfits(player)[id]==true
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
    -- Official limited-outfit probabilities, in basis points.
    return 2+math.max(0,math.min(nextPull,49)-25)
        +math.max(0,math.min(nextPull,69)-49)*2
        +math.max(0,math.min(nextPull,79)-69)*15
        +math.max(0,nextPull-79)*50
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

function E.grantTestCredits(player)
    if (isClient and isClient()) or (isServer and isServer()) then return end
    wallet(player).credits=math.min(9007199254740991,E.balance(player,"credits")+50000)
    return true
end

function E.toggleRecordingUnlocks(player)
    if E.serverWallet or (isClient and isClient()) or (isServer and isServer()) then return nil end
    local enabled=recordingUnlocks[player]==nil
    recordingUnlocks[player]=enabled and {} or nil
    return enabled
end

local legacyWeapon="GFL2.CauseAndEffect"

local function weaponWallet(player)
    local room=wallet(player)
    if E.serverWallet or (isClient and isClient()) then return room end
    local slots=ModData.getOrCreate("GFL2ExiliumSignatureWeapons")
    local slot=tostring(player:getPlayerNum())
    local progress=slots[slot]
    if not progress then
        progress={
            signatureWeapons=room.signatureWeapons,
            signatureWeaponMisses=room.signatureWeaponMisses,
            signatureWeapon=room.signatureWeapon,
        }
        slots[slot]=progress
    end
    return progress
end

function E.weaponProgress(player,id)
    local room=weaponWallet(player)
    local owned=(room.signatureWeapons and room.signatureWeapons[id])
        or (id==legacyWeapon and room.signatureWeapon)
    local temporary=recordingUnlocks[player]
    if temporary and not (owned and owned.acquired) then
        if not temporary[id] then temporary[id]={acquired=true} end
        return temporary[id]
    end
    return owned or {}
end

function E.weaponMisses(player)
    local room=weaponWallet(player)
    local n=room.signatureWeaponMisses
    if n==nil and room.signatureWeapon then n=room.signatureWeapon.misses end
    return type(n)=="number" and n>=0 and math.min(E.weaponGuarantee-1,math.floor(n)) or 0
end

function E.weaponChance(pull)
    if pull>=E.weaponGuarantee then return 10000 end
    return 70+math.max(0,pull-50)*500
end

function E.drawWeapon(player,weapon,count,roll)
    if isClient and isClient() and not E.serverWallet then return "unavailable" end
    if E.weaponProgress(player,weapon.id).acquired or not E.characterUnlocked(player,weapon.character) then return "unavailable" end
    local tickets=E.balance(player,"targeted")
    local status,results,misses=draw(count,tickets,E.weaponMisses(player),E.weaponGuarantee,E.weaponChance,roll)
    if results then
        wallet(player).targeted=tickets-#results
        local room=weaponWallet(player)
        room.signatureWeaponMisses=misses
        if status=="unlocked" then
            room.signatureWeapons=room.signatureWeapons or {}
            room.signatureWeapons[weapon.id]={acquired=true}
        end
    end
    return status,results
end

function E.hasWeaponInInventory(player,id)
    return player:getInventory():containsTypeRecurse(id)
end

function E.claimWeapon(player,weapon)
    if isClient and isClient() and not E.serverWallet then return "unavailable" end
    local progress=E.weaponProgress(player,weapon.id)
    if not progress.acquired then return "unavailable" end
    if E.hasWeaponInInventory(player,weapon.id) then return "alreadyCarried" end
    local inventory=player:getInventory()
    local item=inventory:AddItem(weapon.id)
    if not item then return "unavailable" end
    if weapon.startsWithMagazine then item:setContainsClip(true) end
    item:getModData().GFL2WeaponSkin=weaponSkins.model(weapon.id,progress.skin)
    progress.claimed=true
    if isServer and isServer() then sendAddItemToContainer(inventory,item) end
    return "ok"
end

function E.setWeaponSkin(player,weapon,skin)
    if isClient and isClient() and not E.serverWallet then return "unavailable" end
    local progress=E.weaponProgress(player,weapon.id)
    local model=weaponSkins.model(weapon.id,skin)
    if not progress.acquired or (skin~="" and not model) then return "unavailable" end
    progress.skin=skin~="" and skin or nil
    local items=player:getInventory():getAllTypeRecurse(weapon.id)
    local server=isServer and isServer()
    local primary,secondary,heldChanged
    if server then primary,secondary=player:getPrimaryHandItem(),player:getSecondaryHandItem() end
    for i=0,items:size()-1 do
        local item=items:get(i)
        local data=item:getModData()
        if data.GFL2WeaponSkin~=model then
            data.GFL2WeaponSkin=model
            if server then
                syncItemModData(player,item)
                if item==primary or item==secondary then heldChanged=true end
            end
        end
    end
    -- The inventory packet does not rebuild observers' equipped item copies.
    if heldChanged then sendEquip(player) end
    player:resetEquippedHandsModels()
    return "ok"
end

function E.weaponMagazine(weapon)
    if not weapon or not weapon.startsWithMagazine then return end
    local magazine=weapon.id.."Magazine"
    if getScriptManager():FindItem(magazine) then return magazine end
end

function E.claimMagazine(player,weapon)
    if isClient and isClient() and not E.serverWallet then return "unavailable" end
    local magazine=E.weaponMagazine(weapon)
    if not magazine or not E.weaponProgress(player,weapon.id).acquired then return "unavailable" end
    local inventory=player:getInventory()
    local item=inventory:AddItem(magazine)
    if not item then return "unavailable" end
    if isServer and isServer() then sendAddItemToContainer(inventory,item) end
    return "ok"
end

-- The UI reads its last server snapshot through the same wallet functions.
function E.setSnapshot(player,snapshot)
    snapshots[player]=snapshot
end

return E
