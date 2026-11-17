local function tick()for _,fn in ipairs(Events.OnTick.handlers)do fn()end end
local ctx=context();Events.OnFillWorldObjectContextMenu.handlers[1](0,ctx,{},false);ctx.items[1].fn(ctx.items[1].target)
local w=AimohPMXWindow.instances[0]
actor.current=0;actor.settings="original-settings"
local original={model=AimohPMXPreview.modelId(actor),settings=actor.settings,profiles={}}
actor.data.AimohPMXPreview=original
local pending,failed,target
function AimohPMXPreview.enableWithSettings(a,i,s)pending=true;failed=false;target=i;return ""end
function AimohPMXPreview.isReady()return not pending and actor.current>=0 end
function AimohPMXPreview.hasModelFailure()return failed end
function AimohPMXPreview.status()return failed and "injected GPU failure" or ""end
local voice=calls.voice
assert(w.actions.enable(actor,1));tick()
assert(actor.data.AimohPMXPreview==original and calls.voice==voice,'Pending model was saved/published or changed voice')
actor.current=target;actor.settings="committed-settings";pending=false;tick()
assert(actor.data.AimohPMXPreview.model==AimohPMXPreview.modelId(actor) and actor.data.AimohPMXPreview.settings=='committed-settings')
assert(calls.voice==voice+1,'Successful switch voice notification missing')
local committed=actor.data.AimohPMXPreview;voice=calls.voice
assert(w.actions.enable(actor,2));pending=false;failed=true;tick();tick()
assert(actor.data.AimohPMXPreview==committed and actor.current==1,'Failed candidate corrupted saved selection')
assert(calls.voice==voice+1 and actor.speech==1,'Failed switch should report once and retain old voice')
failed=false;assert(w.actions.enable(actor,2));w.actions.disable(actor);pending=false;tick()
assert(actor.data.AimohPMXPreview.model==nil and actor.current==-1,'Disabled pending model resurrected saved selection')
assert(actor.data.untouched=='keep','Unrelated save data changed')
return 'PASS_KAHLUA_DEFERRED_SAVE_VOICE_FAILURE_AND_CANCEL'
