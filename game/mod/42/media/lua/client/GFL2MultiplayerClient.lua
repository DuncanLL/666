local M = {}
if not isClient() then return M end

local protocol = require "GFL2MultiplayerProtocol"
local economy = require "GFL2RoomEconomy"

local slots = {}
local remote = {}
local nextUpdate = 0
local lastSnapshotRevision = 0
local nextHandshake = 0

local function slot(player)
    local id = player:getOnlineID()
    if id < 0 or player:isDead() then return end
    local state = slots[id]
    if not state or state.player ~= player then
        if state then economy.setSnapshot(state.player, nil) end
        nextHandshake = nextHandshake + 1
        state = {player = player, request = 0, ready = false, nextSync = 0, handshake = nextHandshake}
        slots[id] = state
    end
    return state
end

local function send(state, command, args)
    args.version = protocol.version
    args.session = state.session
    sendClientCommand(state.player, protocol.module, command, args)
end

function M.ready(player)
    local state = slot(player)
    return state and state.ready
end

function M.pending(player)
    local state = slot(player)
    return state and state.pending ~= nil
end

function M.transact(player, command, target, count, callback)
    local state = slot(player)
    if not state or not state.ready or state.pending then return false end
    local args = {request = state.request + 1, target = target, count = count}
    state.pending = {command = command, args = args, callback = callback, retryAt = getTimestampMs() + 3000}
    send(state, command, args)
    return true
end

function M.publish(player, saved)
    if not player:isLocalPlayer() or not M.ready(player) then return end
    local model = saved and saved.model or ""
    local signature = model ~= "" and GFL2CharacterRuntime.networkSignature(model) or ""
    local appearance = model ~= "" and GFL2CharacterRuntime.networkAppearance(player) or ""
    local args = {model = model, signature = signature, appearance = appearance}
    if not protocol.appearance(args) then return end
    local state = slot(player)
    local previous = state.lastPublished
    if previous and previous.model == model and previous.signature == signature and previous.appearance == appearance then return end
    state.attempt = (state.attempt or 0) + 1
    args.attempt = state.attempt
    state.lastPublished = args
    send(state, "Appearance", args)
end

function M.resync()
    for _, state in pairs(slots) do state.nextSync = 0 end
    for _, state in pairs(remote) do
        if state.failed then state.failed = false;state.applied = false end
    end
end

local function acceptAppearance(data, fromSnapshot)
    if not protocol.appearance(data) or not protocol.integer(data.player, 0, 32767)
        or not protocol.integer(data.revision, 1, 2147483647) or type(data.owner) ~= "string" then return end
    if not fromSnapshot and data.revision <= lastSnapshotRevision then return end
    local current = remote[data.player]
    if current and current.revision >= data.revision then return end
    local player = getPlayerByOnlineID(data.player)
    if player and player:isLocalPlayer() then return end
    if current then
        local sameSession = current.owner == data.owner and (not current.session or current.session == data.session)
        if sameSession and current.retired and current.session and data.model ~= "" then return end
        data.previousActor = current.previousActor
        if current.actor then
            if sameSession and not current.actor:isDead() then data.actor = current.actor
            else
                GFL2CharacterRuntime.disable(current.actor)
                data.previousActor = current.actor
            end
        end
    end
    remote[data.player] = data
end

local function updateRemote()
    for id, state in pairs(remote) do
        local actor = getPlayerByOnlineID(id)
        if actor and (actor == state.previousActor or actor:isLocalPlayer() or actor:isDead()
                or actor:getUsername() ~= state.owner) then actor = nil end
        if state.actor and state.actor ~= actor then
            GFL2CharacterRuntime.disable(state.actor)
            state.previousActor = state.actor
            state.actor = nil
            state.applied = false
            state.retired = true
        end
        if state.actor and state.applied and GFL2CharacterRuntime.hasModelFailure(state.actor) then
            GFL2CharacterRuntime.disable(state.actor)
            state.actor = nil
            state.failed = true
        end
        if actor and not state.applied and not state.failed and not state.retired then
            local problem = GFL2CharacterRuntime.queueRemoteAppearance(actor, state.model, state.signature, state.appearance)
            if problem == "" then state.actor = actor;state.applied = true;state.previousActor = nil
            else
                state.failed = true
                print("[GFL2Exilium] Remote appearance: " .. problem)
                GFL2CharacterRuntime.disable(actor)
            end
        end
    end
end

local function restoreAppearance(state, equipped)
    if not protocol.appearance(equipped) or equipped.model == "" then return end
    if GFL2CharacterRuntime.networkSignature(equipped.model) ~= equipped.signature then return end
    local saved = state.player:getModData().GFL2CharacterRuntime
    local settings = saved and (saved.model == equipped.model and saved.settings
        or saved.profiles and saved.profiles[equipped.model]) or ""
    local result = GFL2CharacterRuntime.queueOwnedAppearance(state.player, equipped.model, equipped.appearance, settings)
    if result == "" then state.restoring = true
    else print("[GFL2Exilium] Restore appearance: " .. result) end
end

local function finishRejection(state)
    local rejection = state.rejection
    if not rejection then return end
    local player = state.player
    if state.lastPublished ~= rejection.sent or GFL2CharacterRuntime.modelId(player) ~= rejection.sent.model then
        state.rejection = nil
        return
    end
    if not GFL2CharacterRuntime.isReady(player) and not GFL2CharacterRuntime.hasModelFailure(player) then return end
    state.rejection = nil
    state.lastPublished = nil
    state.restoring = false
    M.onRejected(player)
    restoreAppearance(state, rejection.equipped)
end

local function acceptWallet(state, data)
    local first = not state.ready
    if state.ready and data.request < state.request then return end
    state.ready = true
    state.session = data.session
    state.request = data.request
    economy.setSnapshot(state.player, data.wallet)
    -- A wallet snapshot may arrive before the transaction reply. Replay that request
    -- to get the cached result instead of reporting a purchase failure.
    if state.pending and (state.pending.args.request < data.request or state.pending.args.request > data.request + 1) then
        local callback = state.pending.callback
        state.pending = nil
        callback({status = "unavailable"})
    end
    if first then restoreAppearance(state, data.equipped) end
end

Events.OnServerCommand.Add(function(module, command, data)
    if module ~= protocol.module or type(data) ~= "table" or data.version ~= protocol.version or not GFL2CharacterRuntime then return end
    if command == "Appearance" then
        acceptAppearance(data)
        return
    end
    local state = slots[data.player]
    if not state or state.player:isDead() or state.player:getOnlineID() ~= data.player then return end
    if data.handshake ~= nil and data.handshake ~= state.handshake then return end
    if state.session ~= nil and state.session ~= data.session then return end
    if command == "Snapshot" then
        if data.revision < lastSnapshotRevision then return end
        lastSnapshotRevision = data.revision
        local present = {}
        for _, appearance in ipairs(data.states or {}) do
            acceptAppearance(appearance, true)
            present[appearance.player] = true
        end
        for id, appearance in pairs(remote) do
            if not present[id] and appearance.revision <= data.revision then
                if appearance.actor then GFL2CharacterRuntime.disable(appearance.actor) end
                remote[id] = nil
            end
        end
    elseif command == "Wallet" then
        acceptWallet(state, data)
    elseif command == "Transaction" and state.pending and state.pending.args.request == data.request then
        local callback = state.pending.callback
        state.pending = nil
        state.request = data.request
        economy.setSnapshot(state.player, data.wallet)
        callback(data.result)
    elseif command == "AppearanceRejected" then
        local sent = state.lastPublished
        if sent and (data.attempt == nil or data.attempt == sent.attempt) then
            state.rejection = {sent = sent, equipped = data.equipped}
            finishRejection(state)
        end
    end
end)

Events.OnTick.Add(function()
    if not GFL2CharacterRuntime then return end
    local now = getTimestampMs()
    if now < nextUpdate then return end
    nextUpdate = now + 250
    for index = 0, 3 do
        local player = getSpecificPlayer(index)
        if player and not player:isDead() then
            local state = slot(player)
            if state then
                state.seenAt = now
                if now >= state.nextSync then
                    state.nextSync = now + (state.ready and 15000 or 3000)
                    if not state.pending then send(state, "Request", {handshake = state.handshake}) end
                end
                if state.pending and now >= state.pending.retryAt then
                    state.pending.retryAt = now + 3000
                    send(state, state.pending.command, state.pending.args)
                end
                finishRejection(state)
                if state.restoring then
                    if GFL2CharacterRuntime.isReady(player) then
                        state.restoring = false
                        M.onRestored(player)
                    elseif GFL2CharacterRuntime.hasModelFailure(player) then state.restoring = false end
                end
            end
        end
    end
    for id, state in pairs(slots) do
        if state.seenAt ~= now then
            economy.setSnapshot(state.player, nil)
            slots[id] = nil
        end
    end
    updateRemote()
end)

local function clear()
    if GFL2CharacterRuntime then
        for _, state in pairs(remote) do
            if state.actor then GFL2CharacterRuntime.disable(state.actor) end
        end
    end
    for _, state in pairs(slots) do economy.setSnapshot(state.player, nil) end
    slots = {}
    remote = {}
    nextUpdate = 0
    lastSnapshotRevision = 0
end

Events.OnDisconnect.Add(clear)
if Events.OnMainMenuEnter then Events.OnMainMenuEnter.Add(clear) end

return M
