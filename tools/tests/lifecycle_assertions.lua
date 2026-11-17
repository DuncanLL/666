local function tick()for _,fn in ipairs(Events.OnTick.handlers)do fn()end end
local ctx=context();Events.OnFillWorldObjectContextMenu.handlers[1](0,ctx,{},false);ctx.items[1].fn(ctx.items[1].target)
local w=GFL2RoomWindow.instances[0]
actor.current=0;actor.settings="original-settings"
local original={model=GFL2CharacterRuntime.modelId(actor),settings=actor.settings,profiles={}}
actor.data.GFL2CharacterRuntime=original
local pending,failed,target
function GFL2CharacterRuntime.enableWithSettings(a,i,s)pending=true;failed=false;target=i;return ""end
function GFL2CharacterRuntime.isReady()return not pending and actor.current>=0 end
function GFL2CharacterRuntime.hasModelFailure()return failed end
function GFL2CharacterRuntime.status()return failed and "injected GPU failure" or ""end
local voice=calls.voice
assert(w.actions.enable(actor,1));tick()
assert(actor.data.GFL2CharacterRuntime==original and calls.voice==voice,'Pending model was saved/published or changed voice')
actor.current=target;actor.settings="committed-settings";pending=false;tick()
assert(actor.data.GFL2CharacterRuntime.model==GFL2CharacterRuntime.modelId(actor) and actor.data.GFL2CharacterRuntime.settings=='committed-settings')
assert(calls.voice==voice+1,'Successful switch voice notification missing')
local committed=actor.data.GFL2CharacterRuntime;voice=calls.voice
assert(w.actions.enable(actor,2));pending=false;failed=true;tick();tick()
assert(actor.data.GFL2CharacterRuntime==committed and actor.current==1,'Failed candidate corrupted saved selection')
assert(calls.voice==voice+1 and actor.speech==1,'Failed switch should report once and retain old voice')
failed=false;assert(w.actions.enable(actor,2));w.actions.disable(actor);pending=false;tick()
assert(actor.data.GFL2CharacterRuntime.model==nil and actor.current==-1,'Disabled pending model resurrected saved selection')
assert(actor.data.untouched=='keep','Unrelated save data changed')
return 'PASS_KAHLUA_DEFERRED_SAVE_VOICE_FAILURE_AND_CANCEL'
