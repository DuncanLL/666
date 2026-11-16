local ctx=context();Events.OnFillWorldObjectContextMenu.handlers[1](0,ctx,{},false);ctx.items[1].fn(ctx.items[1].target)
local w=AimohPMXWindow.instances[0]
assert(w.chestButton==nil,'Fixed chest physics must not expose a menu entry')
assert(w.chestToggleButton and w.actions.chestPhysics and not w.actions.chestDefaults)
assert(w.chestToggleButton.visible==false,'Chest switch is hidden before selecting a supported appearance')
local resets=calls.physicsReset
assert(w.actions.physicsReset(actor) and calls.physicsReset==resets+1,'Manual physics reset remains available')
w:close();assert(not w.inManager)
return 'PASS_FIXED_PHYSICS_MENU_AND_RESET'
