local M = {}
if not isClient() then return M end

local protocol = require "GFL2MultiplayerProtocol"
local economy = require "AimohPMXRoomEconomy"

local slots = {}
local remote = {}
local nextUpdate = 0
local lastSnapshotRevision = 0

local function slot(player)
    local id = player:getOnlineID()
    if id < 0 then return end
    local state = slots[id]
    if not state or state.player ~= player then
        if state then economy.setSnapshot(state.player, nil) end
        state = {player = player, request = 0, ready = false, nextSync = 0}
        slots[id] = state
    end
    return state
end

local function send(player, command, args)
    args.version = protocol.version
    sendClientCommand(player, protocol.module, command, args)
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
    send(player, command, args)
    return true
end

function M.publish(player, saved)
    if not player:isLocalPlayer() or not M.ready(player) then return end
    local model = saved and saved.model or ""
    local signature = model ~= "" and AimohPMXPreview.networkSignature(model) or ""
    local appearance = model ~= "" and AimohPMXPreview.networkAppearance(player) or ""
    local args = {model = model, signature = signature, appearance = appearance}
    if not protocol.appearance(args) then return end
    local state = slot(player)
    local previous = state.lastPublished
    if previous and previous.model == model and previous.signature == signature and previous.appearance == appearance then return end
    state.lastPublished = args
    send(player, "Appearance", args)
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
    if current and current.actor then
        if current.owner == data.owner then data.actor = current.actor
        else AimohPMXPreview.disable(current.actor) end
    end
    remote[data.player] = data
end

local function updateRemote()
    for id, state in pairs(remote) do
        local actor = getPlayerByOnlineID(id)
        if actor and (actor:isLocalPlayer() or actor:getUsername() ~= state.owner) then actor = nil end
        if state.actor and state.actor ~= actor then
            AimohPMXPreview.disable(state.actor)
            state.actor = nil
            state.applied = false
        end
        if state.actor and state.applied and AimohPMXPreview.hasModelFailure(state.actor) then
            AimohPMXPreview.disable(state.actor)
            state.actor = nil
            state.failed = true
        end
        if actor and not state.applied and not state.failed then
            local problem = AimohPMXPreview.queueRemoteAppearance(actor, state.model, state.signature, state.appearance)
            if problem == "" then state.actor = actor;state.applied = true
            else
                state.failed = true
                print("[GFL2Exilium] Remote appearance: " .. problem)
                AimohPMXPreview.disable(actor)
            end
        end
    end
end

local function acceptWallet(state, data)
    local first = not state.ready
    if state.ready and data.request < state.request then return end
    state.ready = true
    state.request = data.request
    economy.setSnapshot(state.player, data.wallet)
    if state.pending and state.pending.args.request ~= data.request + 1 then
        local callback = state.pending.callback
        state.pending = nil
        callback({status = "unavailable"})
    end
    if not first then return end
    local equipped = data.equipped
    if not protocol.appearance(equipped) or equipped.model == "" then return end
    if AimohPMXPreview.networkSignature(equipped.model) ~= equipped.signature then return end
    local saved = state.player:getModData().AimohPMXPreview
    local settings = saved and saved.model == equipped.model and saved.settings or ""
    local result = AimohPMXPreview.queueOwnedAppearance(state.player, equipped.model, equipped.appearance, settings)
    if result == "" then state.restoring = true
    else print("[GFL2Exilium] Restore appearance: " .. result) end
end

Events.OnServerCommand.Add(function(module, command, data)
    if module ~= protocol.module or type(data) ~= "table" or data.version ~= protocol.version or not AimohPMXPreview then return end
    if command == "Appearance" then
        acceptAppearance(data)
        return
    end
    local state = slots[data.player]
    if not state then return end
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
                if appearance.actor then AimohPMXPreview.disable(appearance.actor) end
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
        state.lastPublished = nil
        AimohPMXPreview.disable(state.player)
    end
end)

Events.OnTick.Add(function()
    if not AimohPMXPreview then return end
    local now = getTimestampMs()
    if now < nextUpdate then return end
    nextUpdate = now + 250
    for index = 0, 3 do
        local player = getSpecificPlayer(index)
        if player and not player:isDead() then
            local state = slot(player)
            if state then
                if now >= state.nextSync then
                    state.nextSync = now + (state.ready and 15000 or 3000)
                    if not state.pending then send(player, "Request", {}) end
                end
                if state.pending and now >= state.pending.retryAt then
                    state.pending.retryAt = now + 3000
                    send(player, state.pending.command, state.pending.args)
                end
                if state.restoring then
                    if AimohPMXPreview.isReady(player) then
                        state.restoring = false
                        M.publish(player, {model = AimohPMXPreview.modelId(player)})
                    elseif AimohPMXPreview.hasModelFailure(player) then state.restoring = false end
                end
            end
        end
    end
    updateRemote()
end)

local function clear()
    if AimohPMXPreview then
        for _, state in pairs(remote) do
            if state.actor then AimohPMXPreview.disable(state.actor) end
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
