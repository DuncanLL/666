local checks=0
local function checkArea(root,b)
 if not b.visible then return end
 for _,fx in ipairs({.01,.5,.99})do for _,fy in ipairs({.01,.5,.99})do
  assert(hit(root,b.x+b.width*fx,b.y+b.height*fy)==b,(b.roomTitle or 'button')..' hit rectangle is covered')
  checks=checks+1
 end end
 assert(b.x>=0 and b.y>=0 and b.x+b.width<=root.width and b.y+b.height<=root.height,'Button outside window')
 if b.roomTitle then
  local drawn=0
  local previous=b.drawTextureScaled;local previousText=b.drawText
  b.drawTextureScaled=function(s,t,x,y,w,h)
   assert(x>=0 and y>=0 and x+w<=s.width+.01 and y+h<=s.height+.01,'Text outside button frame');drawn=drawn+1
  end
  b.drawText=function(s,t,x,y)
   assert(x>=0 and y>=0 and x+getTextManager():MeasureStringX(UIFont.Small,t)<=s.width,'Fallback text outside button frame');drawn=drawn+1
  end
  if b.roomActive~=nil then b:prerender()else b:render()end
  b.drawTextureScaled=previous;b.drawText=previousText;assert(drawn>0,'Label was not drawn: '..b.roomTitle)
 end
end
local ctx=context();Events.OnFillWorldObjectContextMenu.handlers[1](0,ctx,{},false);ctx.items[1].fn(ctx.items[1].target)
local w=AimohPMXWindow.instances[0];local character
for _,c in ipairs(w.characters)do for _,skin in ipairs(c.skins)do if skin.id=='pmx-c7270af966e8aaf8'then character=c end end end
assert(character,'Eye-enabled character missing');w:onCharacter(character);w:update()
assert(w.eyeGroup~=nil and w.eyeOpenButton.visible and w.eyeClosedButton.visible)
click(w,w.eyeClosedButton);assert(AimohPMXPreview.selectedOption(actor,w.eyeGroup)==1)
click(w,w.eyeOpenButton);assert(AimohPMXPreview.selectedOption(actor,w.eyeGroup)==0)
assert(actor.data.AimohPMXPreview.profiles[AimohPMXPreview.modelId(actor)]==actor.settings,'Eye selection not saved')
for _,size in ipairs({{1100,670},{960,620},{736,520}})do
 w:close();AimohPMXWindow.instances={};screenWidth=size[1]+64;screenHeight=size[2]+80
 w=AimohPMXWindow.open(actor,w.actions)
 -- Width is fixed at construction; test each full tab's control ordering.
 for _,tab in ipairs({'skins','parts','advanced','chest'})do
  w:setTab(tab)
  for _,b in ipairs({w.closeButton,w.refreshButton,w.chestButton,w.pageButton,w.skinButton,w.wearButton,w.advancedButton,w.physicsResetButton,w.restoreButton,w.resetButton,w.reloadButton,w.eyeOpenButton,w.eyeClosedButton})do checkArea(w,b)end
  for _,b in ipairs(w.content.children)do
   if b.roomTitle and b.y>=0 and b.y+b.height<=w.content.height then checkArea(w.content,b)end
  end
 end
 assert(w.pageButton.parent==w.chestButton.parent and w.pageButton.y==w.chestButton.y,'Parts and chest must be peer navigation')
 click(w,w.pageButton);assert(w.tab=='parts' and w.advancedButton.visible)
 click(w,w.advancedButton);assert(w.tab=='advanced')
 click(w,w.chestButton);assert(w.tab=='chest' and not w.advancedButton.visible)
 local sliders=0;for _,widget in ipairs(w.widgets)do if widget.isSlider then sliders=sliders+1 end end
 assert(sliders==4,'Chest navigation must open its four sliders directly')
 local underlay=false;local drawRect=w.content.drawRect
 w.content.drawRect=function(s,x,y,width,height,alpha,r,g,b)if alpha>=.8 and r<.1 and g<.1 and b<.1 then underlay=true end end
 w.content:prerender();w.content.drawRect=drawRect;assert(underlay,'Controls require a readable dark underlay')
 w:setTab('skins');click(w,w.eyeClosedButton);click(w,w.eyeOpenButton)
 assert(not w.preview.dragging,'Eye button initiated preview rotation')
 local x,y=(w.sidebar.x+w.sidebar.width+w.rightX)/2,w.preview.y+120
 assert(hit(w,x,y)==w.preview,'Preview interaction was disabled')
 w.preview:onMouseDown();w.preview:onMouseMove(12,0);w.preview:onMouseUp();assert(w.yaw>0 and not w.preview.dragging)
end
w:close()
return 'PASS_ROOM_HIT_REGIONS_REAL_BUTTON_HANDLERS_EYES_SAVE_'..checks
