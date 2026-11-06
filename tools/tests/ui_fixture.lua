-- Test-only widget and actor substitutes; real game Kahlua and translation parser run the files.
UI={}
function UI:derive(name) local c={};c.__index=c;setmetatable(c,{__index=self});return c end
function UI:new(x,y,w,h) return setmetatable({x=x,y=y,width=w,height=h,children={},visible=true,backgroundColor={a=0.8},borderColor={a=1},backgroundColorMouseOver={a=1}},self) end
UI.__index=UI
function UI:initialise() end
function UI:createChildren() end
function UI:update() end
function UI:addChild(c) c.parent=self;table.insert(self.children,c) end
function UI:removeChild(c) for i,v in ipairs(self.children)do if v==c then table.remove(self.children,i);break end end end
function UI:addToUIManager() self.inManager=true;if not self.created then self.created=true;self:createChildren() end end
function UI:removeFromUIManager() self.inManager=false end
function UI:setVisible(v) self.visible=v end
function UI:setX(v) self.x=v end
function UI:setY(v) self.y=v end
function UI:setWidth(v) self.width=v end
function UI:setHeight(v) self.height=v end
function UI:getWidth()return self.width end
function UI:getHeight()return self.height end
function UI:getYScroll()return 0 end
function UI:bringToTop()end
function UI:backMost()
 local parent=self.parent
 if parent then parent:removeChild(self);table.insert(parent.children,1,self)end
end
function UI:addScrollBars()end
function UI:setScrollChildren(value)self.scrollChildren=value end
function UI:setScrollHeight(value)self.scrollHeight=value end
function UI:setStencilRect()end
function UI:clearStencilRect()end
function UI:drawRect(x,y,w,h,a,r,g,b)self.lastAlpha=self.lastAlpha or a end
function UI:drawRectBorder()end
function UI:drawText()end
function UI:setEnable(v)self.enabled=v end
ISPanel=UI:derive("ISPanel")
ISButton=UI:derive("ISButton")
function ISButton:new(x,y,w,h,title,target,callback)
 local b=UI.new(self,x,y,w,h);b.title=title;b.target=target;b.onclick=callback;return b
end
ISCollapsableWindow=UI:derive("ISCollapsableWindow")
function ISCollapsableWindow:createChildren()
 for _,key in ipairs({"closeButton","collapseButton","pinButton","infoButton"})do
  self[key]=ISButton:new(0,0,20,20,"",self,function(s)s:close()end);self:addChild(self[key])
 end
end
ISScrollingListBox=UI:derive("ISScrollingListBox")
function ISScrollingListBox:new(x,y,w,h)local b=UI.new(self,x,y,w,h);b.items={};return b end
function ISScrollingListBox:clear()self.items={}end
function ISScrollingListBox:addItem(text,item)table.insert(self.items,{text=text,item=item,index=#self.items+1,height=self.itemheight});return self.items[#self.items]end
function ISScrollingListBox:setOnMouseDownFunction(target,callback)self.target=target;self.onmousedown=callback end
ISComboBox=UI:derive("ISComboBox")
function ISComboBox:new(x,y,w,h,target,callback,arg)local b=UI.new(self,x,y,w,h);b.options={};b.selected=1;b.target=target;b.onChange=callback;b.arg=arg;return b end
function ISComboBox:addOption(text)table.insert(self.options,text)end
function ISComboBox:hidePopup()self.expanded=false;self.popupClosed=true end
UIFont={Small="small",Medium="medium"}
function getCore()return {getScreenWidth=function()return screenWidth or 1920 end,getScreenHeight=function()return screenHeight or 1080 end}end
function require(name)
 if name=="AimohPMXWindow" then return AimohPMXWindow end
 if name=="AimohPMXProtocol" then return protocolFixture end
 return {}
end
calls={enable=0,choice=0,material=0,preset=0,reset=0,reload=0,restore=0,clear=0,test=0,sent={}}
actor={data={untouched="keep"},current=-1,settings="",speech=0}
function actor:getModData()return self.data end
function actor:getPlayerNum()return 0 end
function actor:getOnlineID()return 7 end
function actor:isLocalPlayer()return true end
function actor:Say(text)self.speech=self.speech+1 end
function getSpecificPlayer(i)if i==0 then return actor end end
client=false
function isClient()return client end
function sendClientCommand(a,b,c,d)table.insert(calls.sent,d or c)end
Events={}
for _,name in ipairs({"OnFillWorldObjectContextMenu","OnGameStart","OnCreatePlayer","OnPlayerDeath","OnMainMenuEnter","OnServerCommand","OnDisconnect"})do
 Events[name]={handlers={}};Events[name].Add=function(fn)table.insert(Events[name].handlers,fn)end
end
ISWorldObjectContextMenu={setTest=function()calls.test=calls.test+1;return true end}
function context()
 local c={items={}}
 function c:addOption(name,target,fn,...)local v={name=name,target=target,fn=fn,args={...}};table.insert(self.items,v);return v end
 return c
end
AimohPMXPreview={}
local api=AimohPMXPreview
function api.modelCount()return 6 end
function api.modelIdAt(i)return string.format("pmx-%016x",i+1)end
function api.modelLabel(i)return modelLabels[i+1]end
function api.modelListed(i)return i~=5 end
function api.modelId(a)return a.current<0 and "" or api.modelIdAt(a.current)end
function api.isEnabled(a)return a.current>=0 end
function api.isReady(a)return a.current>=0 end
function api.status()return "" end
function api.enableWithSettings(a,i,s)
 if calls.fail then return "invalid settings" end
 calls.enable=calls.enable+1;calls.savedArg=s;a.current=i;a.settings=s~="" and s or "default-settings";return ""
end
function api.appearanceJson(a)return a.settings end
function api.settingsJson(a)return a.settings end
function api.savedSettings(id,a)return a or "default-settings" end
function api.networkSignature(id)return string.rep("f",64)end
function api.groupCount(a)return 2 end
function api.groupLabel(a,g)return "Group "..g end
function api.optionCount(a,g)return 2 end
function api.optionLabel(a,g,o)return "Option "..o end
function api.selectedOption(a,g)return calls.option or 0 end
function api.setChoice(a,g,o)
 if calls.fail then return "invalid choice" end
 calls.choice=calls.choice+1;calls.option=o;a.settings="choice-settings-"..o;return ""
end
function api.presetCount(a)return 1 end
function api.presetLabel(a,i)return "Material preset" end
function api.setPreset(a,i)calls.preset=calls.preset+1;a.settings="preset-settings";return "" end
function api.materialCount(a)return 4 end
function api.materialIndex(a,row)return row end
function api.materialLabel(a,i)return "Part "..i end
function api.materialVisible(a,i)return calls.hidden~=i end
function api.materialAvailable(a,i)return i~=3 end
function api.setMaterialVisible(a,i,v)calls.material=calls.material+1;calls.hidden=v and -1 or i;a.settings="part-settings-"..i;return "" end
function api.resetAppearance(a)calls.reset=calls.reset+1;a.settings="default-settings";calls.hidden=-1;return ""end
function api.reloadAppearance(a)calls.reload=calls.reload+1;return ""end
function api.disable(a)a.current=-1 end
function api.refreshLibrary()return ""end
function api.queueSettings(a,id,s)calls.restore=calls.restore+1;return ""end
function api.queueRestore(a,id,s)calls.restore=calls.restore+1;return ""end
function api.clear()calls.clear=calls.clear+1;actor.current=-1 end
function api.receiveNetwork()return ""end
