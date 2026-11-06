function getText(key)return key end
function UI:getScrollHeight()return self.scrollHeight or self.height end
function UI:setYScroll(v)self.yscroll=v end
function UI:getYScroll()return self.yscroll or 0 end
function UI:setCapture(v)self.capture=v end
function UI:getAbsoluteX()return self.x+(self.parent and self.parent:getAbsoluteX() or 0)end
function UI:getAbsoluteY()return self.y+(self.parent and self.parent:getAbsoluteY() or 0)end
function UI:isMouseOverScrollBar()return false end
function ISScrollingListBox:onMouseMove()end
function ISScrollingListBox:onMouseUp()end
function ISScrollingListBox:onMouseUpOutside()end
function ISScrollingListBox:onMouseDown(x,y)
 local at=math.floor(y/self.itemheight)+1
 if self.items[at]then self.onmousedown(self.target,self.items[at].item)end
end
function UI:drawTextureScaled(t,x,y,w,h,a,r,g,b)recordDraw(self,"image",x,y,w,h,a,r,g,b,t.path)end
function UI:drawTextureScaledAspect(t,x,y,w,h,a,r,g,b)recordDraw(self,"aspect",x,y,w,h,a,r,g,b,t.path)end
function UI:drawRect(x,y,w,h,a,r,g,b)recordDraw(self,"rect",x,y,w,h,a,r,g,b,"")end
function UI:drawRectBorder(x,y,w,h,a,r,g,b)recordDraw(self,"border",x,y,w,h,a,r,g,b,"")end
function UI:drawText(t,x,y,r,g,b,a,font)recordDraw(self,"text",x,y,0,18,a,r,g,b,t)end
function getTexture(path)return {path=path}end
local originalRequire=require
function require(name)if name=="AimohPMXRoomData"then return function()return roomData end end;return originalRequire(name)end
local api=AimohPMXPreview
function api.modelCount()return #registryModels end
function api.modelIdAt(i)return registryModels[i+1].id end
function api.modelLabel(i)return registryModels[i+1].label end
function api.modelListed(i)return not registryModels[i+1].hidden end
function api.modelId(a)return a.current<0 and "" or api.modelIdAt(a.current)end
function api.isReady(a)return api.isEnabled(a) and not calls.notReady end
function api.hasModelFailure(a)return calls.modelFailed or false end
function api.groupCount(a)return #(registryModels[a.current+1].groups or {})end
function api.groupLabel(a,g)return registryModels[a.current+1].groups[g+1].label end
function api.optionCount(a,g)return #registryModels[a.current+1].groups[g+1].options end
function api.optionLabel(a,g,o)local v=registryModels[a.current+1].groups[g+1].options[o+1];return v.label or v.id end
calls.choices={}
function api.selectedOption(a,g)return calls.choices[g] or 0 end
function api.setChoice(a,g,o)
 assert(g>=0 and g<api.groupCount(a));assert(o>=0 and o<api.optionCount(a,g))
 if calls.fail then return "invalid choice"end
 calls.choice=calls.choice+1;calls.choices[g]=o;a.settings="choice-settings-"..g.."-"..o;return ""
end
AimohPMXReadyRoom={draw=function(...)calls.draw=(calls.draw or 0)+1;return true end,release=function(a)calls.release=(calls.release or 0)+1 end}
function paint(panel)
 if not panel.visible then return end
 if panel.prerender then panel:prerender()end
 if panel.items then
  local y=panel:getYScroll()
  for _,item in ipairs(panel.items)do panel:doDrawItem(y,item,false);y=y+item.height end
 end
 for _,child in ipairs(panel.children)do paint(child)end
 if panel.render then panel:render()end
end
