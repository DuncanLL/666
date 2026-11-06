-- Shared arbitration; pools, cue names and configured repeat rules belong to each voice profile.
AimohPMXVoiceCore=AimohPMXVoiceCore or {}
function AimohPMXVoiceCore.create(config)
local C={rules=config.rules,defaults=config.defaults,pools=config.pools}
C.defaults.sameCueMs=20*60*1000
C.defaults.sameConditionMs=C.defaults.sameCueMs
function C.newState(now, seed)
    return { initialized=false, levels={}, maxPriority=0, maxKey=nil,
        rng=((seed or now or 1) % 2147483646)+1, nextOrdinary=0, nextUrgent=0,
        lastUrgentPriority=0, lastUrgentAt=-1000000000, lastManualAt=-1000000000,
        cueAt={}, cuePriority={}, conditionAt={}, lastPool={},
        speakingUntil=0, currentPriority=0, currentCue=nil,
        samples=0, moodleReads=0, spoken=0,killSpoken=0,
        autoSpoken=0, manualSpoken=0, rejected=0, highest=nil,
        lastReject=nil }
end

function C.random(state)
    state.rng=(state.rng*48271) % 2147483647
    return state.rng/2147483647
end

function C.pick(state, pool, key)
    local index=math.floor(C.random(state)*#pool)+1
    if #pool>1 and pool[index]==state.lastPool[key] then index=index % #pool+1 end
    return pool[index]
end

function C.better(a,b)
    if not a then return b end
    if not b then return a end
    if b.priority>a.priority then return b end
    return a
end

function C.observe(state, readLevel)
    local best=nil
    local highest=nil
    local first=not state.initialized
    state.samples=state.samples+1
    state.maxPriority=0
    state.maxKey=nil
    for i=1,#C.rules do
        local rule=C.rules[i]
        local level=math.floor(tonumber(readLevel(rule)) or 0)
        level=math.max(0,math.min(#rule.cues,level))
        local previous=state.levels[rule.key] or 0
        local cue=rule.cues[level]
        local priority=rule.ranks[level] or 0
        local oldPriority=rule.ranks[previous] or 0
        state.moodleReads=state.moodleReads+1
        if priority>state.maxPriority then
            state.maxPriority=priority
            state.maxKey=rule.key
            highest={cue=cue,priority=priority,key=rule.key,kind="state"}
        end
        -- Consume all changes immediately, including those that lose arbitration.
        -- A later cooldown expiry does not replay a queue of old state changes.
        if not first and level>previous and cue and
            (cue~=rule.cues[previous] or (priority>=80 and oldPriority<80)) then
            best=C.better(best,{cue=cue,priority=priority,key=rule.key,kind="state"})
        end
        state.levels[rule.key]=level
    end
    state.initialized=true
    state.highest=highest
    if first and highest and highest.priority>=80 then best=highest end
    if best and best.priority<state.maxPriority then return nil end
    return best
end

-- Select only currently eligible states, so a cooling state cannot block another.
function C.current(state,now,config)
    local best=nil
    for i=1,#C.rules do
        local rule=C.rules[i]
        local level=state.levels[rule.key] or 0
        local priority=rule.ranks[level] or 0
        if rule.cues[level] and (not best or priority>best.priority) then
            best=C.better(best,C.propose(state,{cue=rule.cues[level],priority=priority,key=rule.key,kind="state"},now,config))
        end
    end
    return best
end

function C.propose(state,candidate,now,config)
    if not candidate or not candidate.cue then return nil end
    local cfg=config or C.defaults
    local priority=candidate.priority
    local manual=candidate.kind=="manual"
    local urgent=priority>=80 and not manual
    local kill=candidate.kind=="kill"
    local function reject(reason)
        state.lastReject=reason
        state.rejected=state.rejected+1
        return nil
    end
    if not manual then
        if candidate.kind~="state" and state.maxPriority>=80 then return reject("critical_status") end
    end
    if now<state.speakingUntil and not manual and
        not (urgent and priority>state.currentPriority) then return reject("voice_busy") end
    if manual then
        -- Every successful vanilla Q is intentional. It has no cooldown.
    elseif urgent then
        local escalation=priority>=90 and priority>state.lastUrgentPriority and
            now-state.lastUrgentAt>=cfg.urgentEscalationGapMs
        if now<state.nextUrgent and not escalation then return reject("urgent_cooldown") end
    elseif not kill and now<state.nextOrdinary then
        return reject("ordinary_cooldown")
    end
    local condition=candidate.kind=="state" and state.conditionAt[candidate.key] or nil
    if condition and now-condition.at<cfg.sameConditionMs and
        (state.levels[candidate.key] or 0)<=condition.level then return reject("same_state_20m") end
    local selected=candidate.cue
    if C.pools then
    local pool = C.pools[candidate.cue] or {candidate.cue}
    local preferred = C.pick(state, pool, candidate.key)
    selected = nil
    for pass=1,2 do
        for _, cue in ipairs(pool) do
            if (pass==1 and cue==preferred) or (pass==2 and cue~=preferred) then
                local last = state.cueAt[cue]
                local escalated = urgent and priority > (state.cuePriority[cue] or 0) and
                    last and now-last >= cfg.urgentEscalationGapMs
                if manual or not last or now-last >= cfg.sameCueMs or escalated then
                    selected = cue
                    break
                end
            end
        end
        if selected then break end
    end
    if not selected then return reject("same_line_20m") end
    else
    local lastCue=state.cueAt[candidate.cue]
    if not manual and lastCue and now-lastCue<cfg.sameCueMs then
        local escalated=urgent and priority>(state.cuePriority[candidate.cue] or 0) and
            now-lastCue>=cfg.urgentEscalationGapMs
        if not escalated then return reject("same_line_20m") end
    end
    end
    local chance=candidate.chance
    if chance==nil then chance=urgent and 1 or cfg.stateChance end
    if chance<1 and C.random(state)>=chance then return reject("chance") end
    if not C.pools then return candidate end
    local offered = {}
    for key, value in pairs(candidate) do offered[key] = value end
    offered.cue = selected
    return offered
end

function C.commit(state,candidate,now,duration,config)
    local cfg=config or C.defaults
    state.currentCue=candidate.cue
    state.currentPriority=candidate.priority
    state.speakingUntil=now+duration*1000+750
    state.currentKind=candidate.kind
    state.lastPool[candidate.key]=candidate.cue
    state.spoken=state.spoken+1
    if candidate.kind=="manual" then
        state.lastManualAt=now
        state.manualSpoken=state.manualSpoken+1
        -- No automatic timer, status snapshot, or automatic repeat clock changes.
        return
    end
    if candidate.kind=="state" then
        state.conditionAt[candidate.key]={at=now,level=state.levels[candidate.key] or 0}
    end
    state.cueAt[candidate.cue]=now
    state.cuePriority[candidate.cue]=candidate.priority
    if candidate.kind=="kill" then state.killSpoken=state.killSpoken+1;return end
    state.autoSpoken=state.autoSpoken+1
    state.nextOrdinary=now+cfg.ordinaryMinMs+
        math.floor(C.random(state)*(cfg.ordinaryMaxMs-cfg.ordinaryMinMs+1))
    if candidate.priority>=80 and candidate.kind~="manual" then
        state.nextUrgent=now+cfg.urgentGapMs
        state.lastUrgentAt=now
        state.lastUrgentPriority=candidate.priority
    end
end

function C.finish(state)
    state.speakingUntil=0
    state.currentCue=nil
    state.currentPriority=0
end



return C
end
return AimohPMXVoiceCore
