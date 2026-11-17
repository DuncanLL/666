if not isServer() then return end

local protocol = require "GFL2MultiplayerProtocol"
local catalog = require "GFL2MultiplayerCatalog"
local economy = require "AimohPMXRoomEconomy"
local weapons = require "GFL2SignatureWeapon"
local sessions = {}
local nextSession = 0
local revision = 0
local modDataKey = "GFL2ExiliumMultiplayerV1"

local function account(player)
    local name = player:getUsername()
    if type(name) ~= "string" or name == "" then return end
    local accounts = ModData.getOrCreate(modDataKey)
    if not accounts[name] then accounts[name] = {wallet = {}, request = 0} end
    return accounts[name]
end

economy.serverWallet = function(player) return account(player).wallet end

local function openModel(id)
    local model = catalog[id]
    if model and getActivatedMods():contains(model.mod) then return model end
end

local function canWear(player, id)
    if id == "" then return true end
    local model = openModel(id)
    return model and economy.characterUnlocked(player, model.character)
        and ((model.price == 0 and not model.outfitDraw) or economy.outfitOwned(player, id))
end

local function reply(player, command, data)
    data.version = protocol.version
    data.player = player:getOnlineID()
    data.session = sessions[data.player].token
    sendServerCommand(player, protocol.module, command, data)
end

local function publish(player, session, state)
    revision = revision + 1
    local id = player:getOnlineID()
    local row = {
        version = protocol.version, player = id, owner = player:getUsername(), revision = revision,
        model = state.model, signature = state.signature, appearance = state.appearance, session = session.token,
    }
    session.appearance = state.model ~= "" and row or nil
    sendServerCommand(protocol.module, "Appearance", row)
end

local function equippedAppearance(player, row)
    local equipped = row.appearance
    if equipped and canWear(player, equipped.model) then return equipped end
end

local function snapshot(player, row, handshake)
    reply(player, "Wallet", {wallet = row.wallet, request = row.request, equipped = equippedAppearance(player, row), handshake = handshake})
    local states = {}
    -- Kahlua object tables do not release disconnected players through weak keys.
    for id, session in pairs(sessions) do
        local current = getPlayerByOnlineID(id)
        if current == session.player and not current:isDead() then
            if session.appearance then states[#states + 1] = session.appearance end
        else
            sessions[id] = nil
        end
    end
    reply(player, "Snapshot", {states = states, revision = revision, handshake = handshake})
end

local function transact(player, command, args)
    if command == "Exchange" then
        if type(args.target) ~= "string" then return "unavailable" end
        if not protocol.integer(args.count, 1, 100000) then return "unavailable" end
        return economy.buy(player, args.target, args.count) and "ok" or "short"
    end

    if command == "WeaponSkin" then
        local id,skin=(args.target or ""):match("^(GFL2%.%w+):(%d*)$")
        local weapon=id and weapons.byId[id]
        if not weapon or not getActivatedMods():contains(weapon.mod) then return "unavailable" end
        return economy.setWeaponSkin(player,weapon,skin)
    end
    if command == "DrawWeapon" or command == "ClaimWeapon" or command == "ClaimMagazine" then
        local weapon = weapons.byId[args.target]
        if not weapon or not getActivatedMods():contains(weapon.mod) then return "unavailable" end
        if command == "ClaimWeapon" then return economy.claimWeapon(player,weapon) end
        if command == "ClaimMagazine" then return economy.claimMagazine(player,weapon) end
        return economy.drawWeapon(player,weapon,args.count)
    end
    local model = type(args.target) == "string" and openModel(args.target)
    if not model then return "unavailable" end
    if command == "Wear" then return canWear(player, args.target) and "ok" or "unavailable" end
    if command == "DrawCharacter" then
        local misses = economy.characterMisses(player)
        local status, results = economy.drawCharacter(player, model.character, args.count)
        return status, results, misses
    end
    if not economy.characterUnlocked(player, model.character) then return "unavailable" end
    if command == "BuyOutfit" and model.price > 0 then
        return economy.buyOutfit(player, args.target, model.price)
    end
    if command == "DrawOutfit" and model.outfitDraw then
        local misses = economy.outfitMisses(player, args.target)
        local status, results = economy.drawOutfit(player, args.target, args.count)
        return status, results, misses
    end
    return "unavailable"
end

local function process(player, row, command, args)
    if not protocol.integer(args.request, 1, 2147483647) then return end
    if args.request == row.request and row.lastResult then
        local last = row.lastRequest
        local same = last and last.command == command and last.target == args.target and last.count == args.count
        local result = same and row.lastResult or {status = "unavailable"}
        reply(player, "Transaction", {request = row.request, result = result, wallet = row.wallet})
        return
    end
    if args.request ~= row.request + 1 then
        reply(player, "Wallet", {wallet = row.wallet, request = row.request})
        return
    end
    local status, results, misses = transact(player, command, args)
    row.request = args.request
    row.lastRequest = {command = command, target = type(args.target)=="string" and args.target or nil,
        count = type(args.count)=="number" and args.count or nil}
    row.lastResult = {status = status, results = results, misses = misses}
    reply(player, "Transaction", {request = row.request, result = row.lastResult, wallet = row.wallet})
end

Events.OnClientCommand.Add(function(module, command, player, args)
    if module ~= protocol.module or not player or type(args) ~= "table" or args.version ~= protocol.version then return end
    local id = player:getOnlineID()
    if id < 0 or player:isDead() or getPlayerByOnlineID(id) ~= player then return end
    local session = sessions[id]
    if command ~= "Request" and (not session or session.player ~= player
            or (args.session ~= nil and args.session ~= session.token)) then return end
    if args.target ~= nil and (type(args.target) ~= "string" or #args.target > 64) then return end
    if args.count ~= nil and not protocol.integer(args.count, 1, 100000) then return end
    local row = account(player)
    if not row then return end
    if command == "Request" then
        if not session or session.player ~= player then
            nextSession = nextSession + 1
            sessions[id] = {player = player, token = nextSession}
        end
        local handshake = protocol.integer(args.handshake, 1, 2147483647) and args.handshake or nil
        snapshot(player, row, handshake)
    elseif command == "Appearance" then
        if not protocol.appearance(args) then return end
        if not canWear(player, args.model) then
            local equipped = equippedAppearance(player, row)
            if not equipped and row.appearance then
                row.appearance = nil
                if session.appearance then
                    publish(player, session, {model = "", signature = "", appearance = ""})
                end
            end
            local attempt = protocol.integer(args.attempt, 1, 2147483647) and args.attempt or nil
            reply(player, "AppearanceRejected", {attempt = attempt, equipped = equipped})
            return
        end
        local state = {model = args.model, signature = args.signature, appearance = args.appearance}
        local previous = session.appearance
        if previous and previous.model == state.model
            and previous.signature == state.signature and previous.appearance == state.appearance then return end
        row.appearance = state.model ~= "" and state or nil
        publish(player, session, state)
    elseif command == "Exchange" or command == "DrawCharacter" or command == "BuyOutfit" or command == "DrawOutfit" or command == "Wear" or command == "DrawWeapon" or command == "ClaimWeapon" or command == "ClaimMagazine" or command == "WeaponSkin" then
        process(player, row, command, args)
    end
end)

Events.OnPlayerDeath.Add(function(player)
    local id = player:getOnlineID()
    local session = sessions[id]
    if not session or session.player ~= player then return end
    if session.appearance then publish(player, session, {model = "", signature = "", appearance = ""}) end
    sessions[id] = nil
end)
