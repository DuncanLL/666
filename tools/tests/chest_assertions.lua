local ctx=context();Events.OnFillWorldObjectContextMenu.handlers[1](0,ctx,{},false);ctx.items[1].fn(ctx.items[1].target)
local w=AimohPMXWindow.instances[0]
local selected
for _,character in ipairs(w.characters)do
 for _,skin in ipairs(character.skins)do if skin.id==chestModelId then selected=character end end
end
assert(selected,'Target chest model missing')
w:onCharacter(selected);w:update();assert(AimohPMXPreview.hasChestPhysics(actor))
assert(w.chestButton.enabled)
w.chestButton.onclick(w.chestButton.target,w.chestButton);assert(w.tab=='chest')
local sliders={}
for _,widget in ipairs(w.widgets)do if widget.isSlider then table.insert(sliders,widget)end end
assert(#sliders==4,'Chest tab must contain four actual slider widgets')
local enable=calls.enable;local voice=calls.voice
for i,s in ipairs(sliders)do
 s:setCurrentValue(i*17);assert(calls.chestValues[i]==i*17)
 assert(actor.data.AimohPMXPreview.settings==actor.settings)
 assert(actor.data.AimohPMXPreview.profiles[AimohPMXPreview.modelId(actor)]==actor.settings)
end
assert(calls.enable==enable,'Sliders must not rebuild model')
assert(calls.voice==voice,'Sliders must not restart voice state')
local children=#w.children
for i=1,10 do w:setTab('parts');w:setTab('chest')end
assert(#w.children==children and #w.content.children==#w.widgets)
local defaults=w.widgets[#w.widgets];defaults.onclick(defaults.target,defaults)
assert(calls.chestValues[1]==45 and calls.chestValues[2]==12 and calls.chestValues[3]==50 and calls.chestValues[4]==100)
w:onRestore();assert(not w.chestButton.enabled)
assert(actor.data.untouched=='keep' and actor.data.AimohPMXPreview.profiles)
w:close();assert(not w.inManager)
return 'PASS_REAL_KAHLUA_REAL_SLIDER_CODE_MENU_ROUTING_SAVE_AND_CLEANUP'
