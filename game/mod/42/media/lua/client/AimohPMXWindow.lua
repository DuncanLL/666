require "ISUI/ISCollapsableWindow"
require "ISUI/ISScrollingListBox"
require "ISUI/ISComboBox"
require "ISUI/ISButton"
require "RadioCom/ISUIRadio/ISSliderPanel"
local loadData=require "AimohPMXRoomData"
local data
local textures={}
local function image(path)
    if not path then return nil end
    if textures[path]==nil then textures[path]=getTexture(path) or false end
    return textures[path] or nil
end
local function tr(key) return data.text[key] or key end
local function label(panel,value,x,y,height,width,r,g,b)
    local row=data.labels[value];local t=row and image(row.path)
    if t then
        local scale=math.min(height/row.height,(width or row.width)/row.width)
        panel:drawTextureScaled(t,x,y,row.width*scale,row.height*scale,1,r or .91,g or .94,b or .94)
    else panel:drawText(value,x,y,r or .91,g or .94,b or .94,1,UIFont.Small) end
end
local function tile(panel,x,y,w,h,path,title,active)
    panel:drawRect(x,y,w,h,.73,.045,.065,.075)
    local t=image(path)
    if t then panel:drawTextureScaledAspect(t,x+2,y+2,w-4,h-25,1,1,1,1)
    else
        panel:drawRect(x+w*.3,y+12,w*.4,math.max(8,h-48),.16,.40,.60,.63)
        panel:drawRectBorder(x+w*.3,y+12,w*.4,math.max(8,h-48),.45,.4,.6,.63)
    end
    panel:drawRect(x,y+h-25,w,25,.82,.035,.05,.055)
    label(panel,title,x+6,y+h-21,19,w-12)
    panel:drawRectBorder(x,y,w,h,active and 1 or .40,active and 1 or .38,active and .58 or .50,active and .17 or .53)
    if active then
        panel:drawRectBorder(x+1,y+1,w-2,h-2,1,1,.58,.17)
        panel:drawRect(x+w-16,y+4,11,11,1,1,.58,.17)
    end
end
AimohPMXWindow=ISCollapsableWindow:derive("AimohPMXWindow")
AimohPMXWindow.instances={}
local W=AimohPMXWindow
function W:titleBarHeight() return 44 end
local function button(parent,x,y,w,title,target,callback)
    local b=ISButton:new(x,y,w,28,"",target,callback);b:initialise();b.roomTitle=title
    b.backgroundColor={r=.045,g=.075,b=.085,a=.82};b.backgroundColorMouseOver={r=.15,g=.25,b=.27,a=.94};b.borderColor={r=.34,g=.56,b=.58,a=.55}
    b.render=function(s) label(s,s.roomTitle,8,5,19,s.width-16) end
    parent:addChild(b);return b
end
local function panel(parent,x,y,w,h)
    local p=ISPanel:new(x,y,w,h);p:initialise();p.background=false;p:addScrollBars();p:setScrollChildren(true)
    p.onMouseWheel=function(s,delta) s:setYScroll(math.max(math.min(0,s.height-s:getScrollHeight()),math.min(0,s:getYScroll()-delta*48)));return true end
    p.prerender=function(s)
        if s.parent.tab~="skins" then s:drawRect(0,0,s.width,s.height,.84,.025,.04,.048)end
        s:setStencilRect(0,0,s.width,s.height)
    end
    p.render=function(s) s:clearStencilRect() end
    parent:addChild(p);return p
end
function W:createChildren()
    ISCollapsableWindow.createChildren(self)
    self.closeButton:setX(self.width-30);self.closeButton:setY(10);self.closeButton:setWidth(20);self.closeButton:setHeight(20)
    self.collapseButton:setVisible(false);self.pinButton:setVisible(false);self.infoButton:setVisible(false)
    self.preview=ISPanel:new(math.floor(self.width*.5-390),50,780,self.height-100);self.preview:initialise();self.preview.background=false
    self.preview.render=function(p)
        local w=p.parent;local ok=false
        if AimohPMXReadyRoom then ok=AimohPMXReadyRoom.draw(w.player,math.floor(p:getAbsoluteX()),math.floor(p:getAbsoluteY()),math.floor(p.width),math.floor(p.height),w.yaw,w.zoom) end
        if not ok then label(p,tr("NoPreview"),18,p.height/2,21,p.width-30) end
    end
    self.preview.onMouseDown=function(p) p.dragging=true;p:setCapture(true);return true end
    self.preview.onMouseMove=function(p,dx,dy)if p.dragging then p.parent.yaw=p.parent.yaw+dx*.008 end;return true end
    self.preview.onMouseMoveOutside=self.preview.onMouseMove
    self.preview.onMouseUp=function(p)p.dragging=false;p:setCapture(false);return true end
    self.preview.onMouseUpOutside=self.preview.onMouseUp
    self.preview.onMouseWheel=function(p,d)p.parent.zoom=math.max(.7,math.min(1.5,p.parent.zoom-d*.08));return true end
    self:addChild(self.preview)
    self.railWidth=104;self.rightWidth=math.max(270,math.floor(self.width*.30));self.rightX=self.width-self.rightWidth-16
    self.sidebar=ISScrollingListBox:new(14,58,self.railWidth,self.height-124);self.sidebar:initialise()
    self.sidebar.itemheight=98;self.sidebar.backgroundColor={r=.025,g=.045,b=.055,a=0};self.sidebar.borderColor.a=0
    self.sidebar:setOnMouseDownFunction(self,self.onCharacter);self.sidebar.doDrawItem=self.drawCharacter
    -- Dragging the rail scrolls; a short click selects only on release.
    self.sidebar.onMouseDown=function(s,x,y) if s:isMouseOverScrollBar() then return end;s.roomDrag=0;s.roomDown=true;s:setCapture(true);return true end
    self.sidebar.onMouseMove=function(s,dx,dy)
        if s.roomDown then
            s.roomDrag=s.roomDrag+math.abs(dx)+math.abs(dy)
            if s.roomDrag>5 then s.smoothScrollTargetY=nil;s:setYScroll(math.max(math.min(0,s.height-s:getScrollHeight()),math.min(0,s:getYScroll()+dy))) end
        else ISScrollingListBox.onMouseMove(s,dx,dy) end
    end
    self.sidebar.onMouseMoveOutside=self.sidebar.onMouseMove
    self.sidebar.onMouseUp=function(s,x,y)
        if s.roomDown and s.roomDrag<=5 then ISScrollingListBox.onMouseDown(s,x,y) end
        s.roomDown=false;s:setCapture(false);ISScrollingListBox.onMouseUp(s,x,y);return true
    end
    self.sidebar.onMouseUpOutside=function(s,x,y) s.roomDown=false;s:setCapture(false);ISScrollingListBox.onMouseUpOutside(s,x,y) end
    self:addChild(self.sidebar)
    self.refreshButton=button(self,14,self.height-55,104,tr("Refresh"),self,self.onLibrary)
    self.content=panel(self,self.rightX,105,self.rightWidth,self.height-247)
    local half=(self.rightWidth-8)/2
    self.pageButton=button(self,self.rightX,self.height-94,half,tr("Parts"),self,function(s)s:setTab("parts")end)
    self.chestButton=button(self,self.rightX+half+8,self.height-94,half,tr("ChestPhysics"),self,function(s)s:setTab("chest")end)
    self.skinButton=button(self,self.rightX,self.height-55,132,tr("Back"),self,function(s)s:setTab("skins")end)
    self.wearButton=button(self,self.rightX+140,self.height-55,self.rightWidth-140,tr("Wear"),self,function(w)w:applyBrowse()end)
    self.advancedButton=button(self,self.rightX,105,self.rightWidth,tr("Advanced"),self,function(s)s:setTab(s.tab=="advanced" and "parts" or "advanced")end)
    self.physicsResetButton=button(self,self.width-362,5,176,tr("PhysicsReset"),self,function(w)w.actions.physicsReset(w.player)end)
    self.physicsResetButton:setHeight(34)
    self.physicsResetButton.backgroundColor={r=.18,g=.125,b=.045,a=.96}
    self.physicsResetButton.backgroundColorMouseOver={r=.32,g=.23,b=.075,a=1}
    self.physicsResetButton.borderColor={r=1,g=.67,b=.24,a=1}
    self.physicsResetButton.render=function(b)label(b,b.roomTitle,8,3,28,b.width-16,1,.91,.64)end
    self.restoreButton=button(self,self.width-174,8,136,tr("Restore"),self,self.onRestore)
    self.resetButton=button(self,self.rightX,64,(self.rightWidth-8)/2,tr("Reset"),self,self.onReset)
    self.reloadButton=button(self,self.rightX+(self.rightWidth+8)/2,64,(self.rightWidth-8)/2,tr("Reload"),self,self.onReload)
    self.eyeOpenButton=button(self,self.rightX,64,(self.rightWidth-8)/2,tr("EyeOpen"),self,function(w)w:onEyes(0)end)
    self.eyeClosedButton=button(self,self.rightX+(self.rightWidth+8)/2,64,(self.rightWidth-8)/2,tr("EyeClosed"),self,function(w)w:onEyes(1)end)
    self.widgets={};self.tab="skins";self:refresh()
end
function W:drawCharacter(y,item,alt)
    local entry=item.item;local active=self.parent.character==entry.name
    local t=image(entry.portrait)
    if t then self:drawTextureScaled(t,15,y+6,64,64,1,1,1,1) end
    local ring=image(data.portraitRing)
    if ring then self:drawTextureScaled(ring,12,y+3,70,70,1,active and 1 or .40,active and .62 or .68,active and .24 or .70) end
    return y+item.height
end
function W:clearContent()
    for _,widget in ipairs(self.widgets) do
        widget:setCapture(false)
        if widget.hidePopup then widget:hidePopup() end
        if widget.deactivateToolTip then widget:deactivateToolTip() end
        self.content:removeChild(widget)
    end
    self.widgets={};self.content:setYScroll(0)
end
function W:addLabel(value,y,textHeight)
    textHeight=textHeight or 20
    local p=ISPanel:new(0,y,self.rightWidth-18,math.max(25,textHeight+6));p:initialise();p.background=false
    p.prerender=function(s)s:drawRect(0,6,3,13,1,.24,.66,.69);label(s,value,10,3,textHeight,s.width-14)end
    self.content:addChild(p);table.insert(self.widgets,p)
end
function W:addTile(x,y,width,height,title,path,active,callback)
    local b=ISButton:new(x,y,width,height,"",self,callback);b:initialise()
    b.roomTitle=title;b.roomActive=active
    b.prerender=function(s)tile(s,0,0,s.width,s.height,path,title,active)end;b.render=function()end
    b:setEnable(not self.waiting);self.content:addChild(b);table.insert(self.widgets,b);return b
end
function W:carousel(character)
    local p=ISPanel:new(0,0,self.content.width-16,self.content.height);p:initialise();p.background=false
    p.entries=character.skins;p.index=1;p.offset=0
    for i,e in ipairs(p.entries)do if e.id==(self.browseId or self.currentId)then p.index=i end end
    local function select(s,index)
        s.index=math.max(1,math.min(#s.entries,index));s.offset=0
        self.browseId=s.entries[s.index].id;self.browsed=s.entries[s.index];self:updateWear()
    end
    p.prerender=function(s)
        s:setStencilRect(0,0,s.width,s.height)
        local height=math.min(345,s.height-32);local width=height*.505;local step=width+14
        local function card(i)
            local active=i==s.index;local scale=active and 1 or .88;local w,h=width*scale,height*scale
            local x=s.width/2-w/2+(i-s.index)*step+s.offset;local y=(s.height-h)/2
            local t=image(s.entries[i].meta.card)
            if t then s:drawTextureScaledAspect(t,x,y,w,h,active and 1 or .65,1,1,1)end
            s:drawRectBorder(x,y,w,h,active and 1 or .25,active and 1 or .4,active and .58 or .5,active and .17 or .5)
            if active then s:drawRectBorder(x+1,y+1,w-2,h-2,1,1,.58,.17)end
        end
        for i=1,#s.entries do if i~=s.index then card(i)end end
        card(s.index)
    end
    p.render=function(s)s:clearStencilRect()end
    p.onMouseDown=function(s)s.dragging=true;s.offset=0;s:setCapture(true);return true end
    p.onMouseMove=function(s,dx,dy)if s.dragging then s.offset=s.offset+dx end;return true end
    p.onMouseMoveOutside=p.onMouseMove
    p.onMouseUp=function(s)
        if s.dragging then
            local distance=s.offset;local step=math.min(345,s.height-32)*.505+14
            if math.abs(distance)>24 then select(s,s.index+(distance<0 and 1 or -1)*math.max(1,math.floor(math.abs(distance)/step+.5)))else select(s,s.index)end
        end
        s.dragging=false;s:setCapture(false);return true
    end
    p.onMouseUpOutside=p.onMouseUp
    p.onMouseWheel=function(s,delta)if delta~=0 then select(s,s.index+(delta>0 and 1 or -1))end;return true end
    self.content:addChild(p);table.insert(self.widgets,p);self.carouselPanel=p;select(p,p.index)
end
function W:updateWear()
    local wearing=self.browsed and self.browsed.id==self.currentId
    self.wearButton.roomTitle=wearing and tr("Wearing")or tr("Wear")
    self.wearButton:setEnable(self.browsed~=nil and not wearing and not self.waiting)
    local editable=AimohPMXPreview.isEnabled(self.player) and not self.waiting and (self.tab~="skins" or wearing)
    self.pageButton:setEnable(editable)
    self.chestButton:setEnable(editable and AimohPMXPreview.hasChestPhysics(self.player))
end
function W:applyBrowse()if self.browsed then self:selectSkin(self.browsed)end end
function W:refresh()
    self.currentId=AimohPMXPreview.modelId(self.player);self.currentLabel=tr("Choose")
    self.characters={};self.byCharacter={};self.sidebar:clear()
    for index=0,AimohPMXPreview.modelCount()-1 do
        local id=AimohPMXPreview.modelIdAt(index);local meta=data.models[id]
        if not meta and not data.excluded[id] then
            local name,skin=AimohPMXPreview.modelLabel(index):match("^(.-) / (.+)$")
            meta={character=name or AimohPMXPreview.modelLabel(index),skin=data.skinNames[skin] or skin or tr("Default")}
        end
        if AimohPMXPreview.modelListed(index) and meta then
            local entry={id=id,index=index,meta=meta};local character=self.byCharacter[meta.character]
            if not character then character={name=meta.character,portrait=meta.portrait,skins={}};self.byCharacter[meta.character]=character;table.insert(self.characters,character) end
            table.insert(character.skins,entry)
            if id==self.currentId then self.character=meta.character;self.currentLabel=meta.skin;self.selected=entry end
        end
    end
    for _,character in ipairs(self.characters) do self.sidebar:addItem(character.name,character) end
    if not self.byCharacter[self.character] then self.character=self.characters[1] and self.characters[1].name end
    self:refreshControls()
end
function W:setTab(tab)self.tab=tab;self:refreshControls()end
function W:partRows(extrasOnly)
            local names=self.selected and self.selected.meta.primaryCategories or {tr("Head"),tr("Top"),tr("Bottom")}
            local primary={};for _,name in ipairs(names)do primary[name]=true end
            local groups=AimohPMXPreview.groupCount(self.player);local categories={};local extra={}
            for group=0,groups-1 do
                local name=AimohPMXPreview.groupLabel(self.player,group)
                local normalized=data.categories[name] or name
                local row={index=group,label=normalized}
                if primary[normalized] then categories[normalized]=row else table.insert(extra,row) end
            end
            local ordered={}
            if extrasOnly then ordered=extra else
                for _,name in ipairs(names)do table.insert(ordered,categories[name] or {label=name})end
            end
            return ordered
end
function W:drawGroups(ordered,y,width)
            for _,row in ipairs(ordered)do
                self:addLabel(row.label,y);y=y+30
                if row.index then
                    local group=row.index;local count=AimohPMXPreview.optionCount(self.player,group)
                    for option=0,count-1 do
                        local w=(width-16)/3;local x=(option%3)*(w+8);local yy=y+math.floor(option/3)*85
                        local optionLabel=AimohPMXPreview.optionLabel(self.player,group,option)
                        local pictures=self.selected.meta.partImages
                        local groupPictures=pictures and pictures[AimohPMXPreview.groupLabel(self.player,group)]
                        self:addTile(x,yy,w,77,optionLabel,groupPictures and groupPictures[optionLabel],AimohPMXPreview.selectedOption(self.player,group)==option,function(s)
                            if s.actions.choice(s.player,group,option) then s:refreshControls() end
                        end)
                    end
                    y=y+math.ceil(count/3)*85+10
                else
                    local p=ISPanel:new(0,y,width,32);p:initialise();p.background=false;p.prerender=function(s)label(s,tr("NoCategory"),5,6,17,s.width-10,.56,.66,.69)end
                    self.content:addChild(p);table.insert(self.widgets,p);y=y+42
                end
            end
    return y
end
function W:refreshControls()
    self:clearContent()
    local enabled=AimohPMXPreview.isEnabled(self.player)
    self.physicsResetButton:setEnable(enabled and not self.waiting);self.restoreButton:setEnable(enabled);self.resetButton:setEnable(enabled and not self.waiting);self.reloadButton:setEnable(enabled and not self.waiting)
    local details=self.tab=="parts" or self.tab=="advanced"
    self.content:setY(details and 143 or 105);self.content:setHeight(self.height-self.content.y-142)
    self.skinButton:setVisible(self.tab~="skins")
    self.skinButton:setEnable(not self.waiting)
    for _,item in ipairs({{self.pageButton,details},{self.chestButton,self.tab=="chest"}})do
        local active=item[2];item[1].borderColor={r=active and 1 or .34,g=active and .58 or .56,b=active and .17 or .58,a=active and 1 or .55}
    end
    self.resetButton:setVisible(self.tab=="advanced");self.reloadButton:setVisible(self.tab=="advanced")
    self.eyeGroup=nil
    local eye=self.selected and self.selected.meta.groups and self.selected.meta.groups.eyes
    if enabled and not self.waiting and eye then
        for i=0,AimohPMXPreview.groupCount(self.player)-1 do
            if AimohPMXPreview.groupLabel(self.player,i)==eye.label then self.eyeGroup=i;break end
        end
    end
    local showEyes=self.eyeGroup~=nil and (self.tab=="skins" or self.tab=="parts")
    for option,b in ipairs({self.eyeOpenButton,self.eyeClosedButton})do
        b:setVisible(showEyes)
        if showEyes then
            local active=AimohPMXPreview.selectedOption(self.player,self.eyeGroup)==option-1
            b.borderColor={r=active and 1 or .34,g=active and .58 or .56,b=active and .17 or .58,a=active and 1 or .55}
        end
    end
    self.advancedButton:setVisible(details)
    self.advancedButton.roomTitle=self.tab=="advanced" and tr("BackParts")or tr("Advanced")
    local y=0;local width=self.rightWidth-18
    if self.tab=="skins" then
        local character=self.byCharacter[self.character]
        if character then self:carousel(character) end
        y=self.content.height
    elseif enabled and not self.waiting and self.selected and self.selected.id==self.currentId then
        if self.tab=="chest" then
            self:addLabel(tr("ChestPhysics"),y);y=y+38
            for index,key in ipairs({"ChestSoftness","ChestDamping","ChestInertia","ChestStrength"})do
                local field=index-1;self:addLabel(tr(key),y,24);y=y+32
                local slider=ISSliderPanel:new(4,y,width-12,24,self,function(w,value,s)
                    if not w.actions.chestPhysics(w.player,field,value)then w.status=tr("Error")end
                end)
                slider:initialise();slider:instantiate();slider:setValues(0,100,1,10,true)
                slider:setCurrentValue(AimohPMXPreview.chestPhysicsSlider(self.player,field),true)
                local base=slider.render
                slider.render=function(s)base(s);s:drawTextRight(tostring(math.floor(s.currentValue+.5)),s.width,-28,1,1,1,1,UIFont.Medium)end
                self.content:addChild(slider);table.insert(self.widgets,slider);y=y+46
            end
            local b=button(self.content,0,y,width,tr("Reset"),self,function(w)if w.actions.chestDefaults(w.player)then w:refreshControls()end end)
            table.insert(self.widgets,b);y=y+36
            if AimohPMXPreview.chestPhysicsStatus(self.player)~="" then self:addLabel(tr("ChestSuspended"),y);y=y+32 end
        elseif self.tab=="advanced" then
            y=self:drawGroups(self:partRows(true),y,width)
            for row=0,AimohPMXPreview.materialCount(self.player)-1 do
                local index=AimohPMXPreview.materialIndex(self.player,row);local available=AimohPMXPreview.materialAvailable(self.player,index)
                local b=button(self.content,0,y,width,AimohPMXPreview.materialLabel(self.player,index),self,function(s)
                    if s.actions.material(s.player,index,not AimohPMXPreview.materialVisible(s.player,index))then s:refreshControls()end
                end)
                b:setEnable(available);b.borderColor.a=AimohPMXPreview.materialVisible(self.player,index) and .95 or .25;table.insert(self.widgets,b);y=y+34
            end
            if AimohPMXPreview.presetCount(self.player)>0 then
                self:addLabel(tr("Presets"),y);y=y+30
                for index=0,AimohPMXPreview.presetCount(self.player)-1 do
                    local b=button(self.content,0,y,width,AimohPMXPreview.presetLabel(self.player,index),self,function(s)if s.actions.preset(s.player,index)then s:refreshControls()end end)
                    table.insert(self.widgets,b);y=y+34
                end
            end
        else
            y=self:drawGroups(self:partRows(false),y,width)
        end
    else self:addLabel(self.waiting and tr("Loading") or tr("Choose"),8);y=56 end
    self:updateWear()
    self.content:setScrollHeight(math.max(y,self.content.height));self.status=self.waiting and tr("Loading") or enabled and tr("Saved") or tr("Original")
end
function W:selectSkin(entry)
    if self.waiting or self.currentId==entry.id then return false end
    if self.actions.enable(self.player,entry.index)then self.waiting=true;self.yaw=0;self.zoom=1.08;self:refresh();return true end
    self.status=tr("Error");return false
end
function W:onCharacter(character)
    if self.waiting then return end
    local previous,tab=self.character,self.tab
    local browseId,browsed=self.browseId,self.browsed
    self.browseId=nil;self.browsed=nil
    self.character=character.name;self.tab="skins"
    local entry=character.skins[1]
    for _,skin in ipairs(character.skins)do if skin.id==self.currentId then entry=skin end end
    if self.currentId==entry.id then self:refreshControls()
    elseif not self:selectSkin(entry)then
        self.character=previous;self.tab=tab;self.browseId=browseId;self.browsed=browsed
        self:refreshControls();self.status=tr("Error")
    end
end
function W:onEyes(option)
    if self.eyeGroup~=nil and self.actions.choice(self.player,self.eyeGroup,option)then self:refreshControls()end
end
function W:onLibrary()if not self.waiting and self.actions.refresh(self.player)then self:refresh()end end
function W:onRestore()self.actions.disable(self.player);if AimohPMXReadyRoom then AimohPMXReadyRoom.release(self.player)end;self.waiting=false;self.selected=nil;self:refresh()end
function W:onReset()if self.actions.reset(self.player)then self:refreshControls()end end
function W:onReload()if self.actions.reload(self.player)then self:refreshControls()end end
function W:update()
    ISCollapsableWindow.update(self)
    if self.waiting then
        if AimohPMXPreview.hasModelFailure and AimohPMXPreview.hasModelFailure(self.player)then self.waiting=false;self:refresh();self.status=tr("Error")
        elseif AimohPMXPreview.isReady(self.player)then self.waiting=false;self:refresh()end
    end
end
function W:prerender()
    self:drawRect(0,0,self.width,self.height,.98,.035,.055,.065)
    local background=image(data.background)
    if background then self:drawTextureScaled(background,1,44,self.width-2,self.height-45,1,1,1,1)end
    self:drawRect(0,0,self.width,44,.96,.035,.065,.075)

    self:drawRectBorder(0,0,self.width,self.height,.8,.27,.48,.50)
    self:drawRect(16,43,52,2,1,.25,.72,.75)
    label(self,tr("Menu"),17,10,27,200)
    if self.tab=="skins" and self.browsed then
        label(self,self.browsed.meta.skin,138,self.height-163,35,self.rightX-156)
    end
end
function W:render()end
function W:close()
    if self.carouselPanel then self.carouselPanel.dragging=false;self.carouselPanel:setCapture(false)end
    self:clearContent();self.preview.dragging=false;self.preview:setCapture(false);self.sidebar.roomDown=false;self.sidebar:setCapture(false)
    if AimohPMXReadyRoom then AimohPMXReadyRoom.release(self.player)end
    self:setVisible(false);self:removeFromUIManager()
end
function W.open(player,actions)
    if not AimohPMXReadyRoom then player:Say(getText("ContextMenu_AimohPMX_JavaUnavailable"));return end
    if not data then data=loadData() end
    local index=player:getPlayerNum();local window=W.instances[index]
    if not window then
        local sw,sh=getCore():getScreenWidth(),getCore():getScreenHeight()
        local width=math.min(1100,sw-64);local height=math.min(670,sh-80)
        window=ISCollapsableWindow.new(W,math.floor((sw-width)/2),math.floor((sh-height)/2),width,height)
        window.player=player;window.actions=actions;window.resizable=false;window.drawFrame=false;window.yaw=0;window.zoom=1.08
        window:initialise();window:addToUIManager();W.instances[index]=window
    else window.actions=actions;window:addToUIManager();window:refresh()end
    window:setVisible(true);window:bringToTop();return window
end
function W.closeAll()for _,window in pairs(W.instances)do window:close()end;W.instances={}end
function W.closeFor(player)
    if not player then return end
    local index=player:getPlayerNum();if W.instances[index]then W.instances[index]:close();W.instances[index]=nil end
end
return W
