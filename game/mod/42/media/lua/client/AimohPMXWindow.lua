require "ISUI/ISCollapsableWindow"
require "ISUI/ISScrollingListBox"
require "ISUI/ISComboBox"
require "ISUI/ISButton"
require "ISUI/ISTextBox"
require "RadioCom/ISUIRadio/ISSliderPanel"
local loadData=require "AimohPMXRoomData"
local economy=require "AimohPMXRoomEconomy"
local data
local textures={}
local function image(path)
    if not path then return nil end
    if not textures[path] then textures[path]=getTexture(path) end
    return textures[path]
end
local art="media/ui/AimohPMXReadyRoom/room-"
local function roomSound(action)
    getSoundManager():playUISound("R116Room"..action)
end
local function lock(panel,x,y)
    panel:drawRect(x,y,21,21,.90,.045,.065,.075)
    panel:drawRectBorder(x+6,y+3,9,10,1,.89,.91,.91)
    panel:drawRect(x+4,y+9,13,9,1,.89,.91,.91)
    panel:drawRect(x+10,y+12,1,4,1,.20,.25,.26)
end
local function clamp(value,lo,hi) return math.max(lo,math.min(hi,value)) end
local function ease(t)
    t=1-clamp(t,0,1)
    return 1-t*t*t
end
local function surface(p,x,y,w,h,a)
    if h<=0 or w<=0 then return end
    p:drawRect(x+2,y,w-4,h,a,.110,.149,.169)
    p:drawRect(x,y+2,2,h-4,a,.110,.149,.169)
    p:drawRect(x+w-2,y+2,2,h-4,a,.110,.149,.169)
    local glass=image(art.."glass.png")
    if glass then p:drawTextureScaled(glass,x,y,w,h,a,1,1,1) end
end
local function frosted(p,w,x,y,width,height,alpha)
    local blur=image(art.."frost-bg.png")
    if blur then
        local dx=w:getAbsoluteX()-p:getAbsoluteX()
        local dy=w:getAbsoluteY()-p:getAbsoluteY()
        if p~=w then dy=dy-p:getYScroll() end
        local scale=math.max(w.width/blur:getWidth(),w.fullHeight/blur:getHeight())
        local bw,bh=blur:getWidth()*scale,blur:getHeight()*scale
        p:setStencilRect(x,y,width,height)
        p:drawTextureScaled(blur,dx+(w.width-bw)/2,dy+(w.fullHeight-bh)/2,bw,bh,.57,1,1,1)
        p:clearStencilRect()
    end
    surface(p,x,y,width,height,alpha)
end
local function chevron(p,x,y,direction,size,alpha)
    local glyph=image(art.."arrow-"..direction..".png")
    if glyph then p:drawTextureScaled(glyph,x-size/2,y-size/2,size,size,alpha,.95,.95,.95) end
end
local function tr(key) return data.text[key] or key end
local function isLocalMotion(file)
    return file~=nil and file:find("[/\\]")~=nil
end
local function motionDisplayName(file)
    if file:sub(1,7)=="sample:" then return file:sub(8) end
    return file:match("([^/\\]+)$") or file
end
local function fitMotionLine(value,width)
    local tm=getTextManager()
    if tm:MeasureStringX(UIFont.Small,value)<=width then return value end
    local lo,hi=0,#value
    while lo<hi do
        local mid=math.floor((lo+hi+1)/2)
        if tm:MeasureStringX(UIFont.Small,value:sub(1,mid).."...")<=width then lo=mid
        else hi=mid-1 end
    end
    return value:sub(1,lo).."..."
end
local function label(panel,value,x,y,height,width,r,g,b,a,centered)
    local row=(data.roomLabels and data.roomLabels[value]) or data.labels[value]
    local t=row and image(row.path)
    if t then
        local scale=math.min(height/row.height,(width or row.width)/row.width)
        local w,h=row.width*scale,row.height*scale
        panel:drawTextureScaled(t,centered and x-w/2 or x,y+(height-h)/2,w,h,a or 1,r or .92,g or .92,b or .91)
    elseif centered then panel:drawTextCentre(value,x,y,r or .92,g or .92,b or .91,a or 1,UIFont.Small)
    else panel:drawText(value,x,y,r or .92,g or .92,b or .91,a or 1,UIFont.Small) end
end
local function description(panel,lines,x,y,width)
    for i,line in ipairs(lines or {}) do
        label(panel,line,x,y+(i-1)*18,15,width,.84,.84,.82)
    end
end
local function featureIcon(p,kind,x,y,a)
    local glyph=image(art..kind..".png")
    if glyph then p:drawTextureScaled(glyph,x,y,29,29,a,.85,.87,.86) end
end

local function expressionPreset(preset,rows)
    local fields={}
    for axis=2,#preset do
        for _,channel in ipairs(preset[axis]) do
            if rows[channel[1]]~=nil then
                fields[#fields+1]='"'..channel[2]..'":'..tostring(channel[3])
                break
            end
        end
    end
    if #preset==1 or #fields>0 then return "{"..table.concat(fields,",").."}" end
end
local function number(p,value,x,y,h,r,g,b,right)
    local text=string.format("%.0f",value)
    local step=h*.63
    if right then x=x-#text*step end
    for i=1,#text do
        label(p,text:sub(i,i),x+(i-1)*step,y,h,step,r,g,b,1)
    end
end

local function tile(panel,x,y,w,h,path,title,active)
    surface(panel,x,y,w,h,.8)
    local t=image(path)
    if t then panel:drawTextureScaledAspect(t,x+2,y+2,w-4,h-25,1,1,1,1)
    else
        panel:drawRect(x+w*.3,y+12,w*.4,math.max(8,h-48),.16,.40,.60,.63)
        panel:drawRectBorder(x+w*.3,y+12,w*.4,math.max(8,h-48),.45,.4,.6,.63)
    end
    panel:drawRect(x,y+h-25,w,25,.82,.035,.05,.055)
    label(panel,title,x+6,y+h-21,19,w-12)
    if active then
        panel:drawRectBorder(x,y,w,h,.84,1,.71,.38)
        panel:drawRect(x,y+h-2,w,2,1,1,.71,.38)
    end
end
AimohPMXWindow=ISCollapsableWindow:derive("AimohPMXWindow")
AimohPMXWindow.instances={}
local W=AimohPMXWindow
function W:titleBarHeight() return 50 end
local function button(parent,x,y,w,title,target,callback)
    local b=ISButton:new(x,y,w,28,"",target,callback);b:initialise();b.roomTitle=title;b:setSound("activate","R116RoomSelect")
    b.backgroundColor={r=.149,g=.204,b=.227,a=.84}
    b.borderColor={r=.45,g=.56,b=.60,a=.38}
    b.roomHover=0;b.roomHoverTo=0
    b.prerender=function(s)
        local enabled=s.enable~=false
        local to=enabled and (s.mouseOver or s.pressed or s.joypadFocused) and 1 or 0
        if to~=s.roomHoverTo then
            s.roomHoverFrom=s.roomHover;s.roomHoverTo=to;s.roomHoverStart=getTimestampMs()
        end
        if s.roomHoverStart then
            local t=clamp((getTimestampMs()-s.roomHoverStart)/115,0,1)
            s.roomHover=s.roomHoverFrom+(to-s.roomHoverFrom)*ease(t)
            if t==1 then s.roomHoverStart=nil end
        end
        local c=s.backgroundColor
        local a=enabled and c.a or .51
        if s.roomTab then a=.35+s.roomHover*.36 end
        if s.roomLook=="bare" then a=s.roomHover*.26 end
        if s.roomLook=="outfit" or s.roomLook=="parts" then c={r=.84,g=.84,b=.86};a=enabled and .99 or .66 end
        if s.roomLook=="navigation" then c={r=.16,g=.22,b=.23};a=.88 end
        if s.roomLook=="primary" then c={r=.220,g=.322,b=.306};a=enabled and .98 or .87 end
        if s.roomLook=="drawOnce" then c=s.roomOutfitDraw and {r=.29,g=.24,b=.69} or {r=.79,g=.80,b=.82};a=.94 end
        if s.roomLook=="outfitPurchase" then c={r=.84,g=.84,b=.86};a=enabled and .99 or .66 end
        if s.roomLook=="drawTen" then c={r=.82,g=.24,b=.08};a=.94 end
        local glow=s.roomHover*(s.roomLook=="outfit" and .08 or .047)
        local r,g,b=c.r+glow,c.g+glow,c.b+glow
        s:drawRect(0,2,s.width,s.height-4,a,r,g,b)
        s:drawRect(2,0,s.width-4,2,a,r,g,b)
        s:drawRect(2,s.height-2,s.width-4,2,a,r,g,b)
        local glass=image(art.."glass.png")
        if glass then s:drawTextureScaled(glass,0,0,s.width,s.height,a,1,1,1) end
        if s.roomActive then s:drawRect(3,s.height-3,s.width-6,2,.92,.875,.620,0) end
    end
    b.render=function(s)
        local enabled=s.enable~=false
        local a=enabled and 1 or .54
        if s.roomArrow then
            chevron(s,s.width/2,s.height/2,s.roomArrow,25,a)
            return
        end
        if s.roomFold then
            local arrow=image(art..(s.parent.folded and "down" or "up")..".png")
            if arrow then s:drawTextureScaled(arrow,(s.width-16)/2,(s.height-10)/2,16,10,a,.83,.85,.84) end
            return
        end
        if s.roomTile then
            featureIcon(s,s.roomTile,s.width/2-14.5,10,a)
            label(s,s.roomTitle,s.width/2,s.height-24,16,s.width-10,.91,.92,.90,a,true)
            return
        end
        if s.roomLook=="outfit" then
            local icon=image(art.."clothes.png")
            if icon then s:drawTextureScaled(icon,(s.width-31)/2,(s.height-31)/2,31,31,a,.15,.21,.23) end
            return
        end
        if s.roomLook=="parts" or s.roomLook=="drawOnce" or s.roomLook=="drawTen" then
            local t=(s.roomLook=="drawTen" or s.roomOutfitDraw) and .94 or .12
            label(s,s.roomTitle,s.width/2,(s.height-19)/2,19,s.width-16,t,t,t,1,true)
            return
        end
        local x=8
        local y=math.floor((s.height-16)/2)+(s.pressed and enabled and 1 or 0)
        if s.roomIcon then
            local icon=image(art..s.roomIcon..".png")
            if icon then s:drawTextureScaled(icon,x,y+2,12,12,a,.89,.91,.89);x=x+16 end
        end
        if s.roomLook=="primary" then
            label(s,s.roomTitle,s.width/2,(s.height-18)/2,18,s.width-14,.92,.92,.91,enabled and 1 or .84,true)
        else label(s,s.roomTitle,x,y,16,s.width-x-6,.91,.92,.90,a) end
    end
    parent:addChild(b);return b
end
local function panel(parent,x,y,w,h)
    local p=ISPanel:new(x,y,w,h);p:initialise();p.background=false;p:addScrollBars();p:setScrollChildren(true)
    local function scroll(s,offset)
        s:setYScroll(clamp(s:getYScroll()+offset,math.min(0,s.height-s:getScrollHeight()),0))
    end
    p.onMouseWheel=function(s,delta)scroll(s,-delta*48);return true end
    p.onMouseDown=function(s)
        if s.parent.tab=="skins" or s:getScrollHeight()<=s.height then return false end
        if s.vscroll and s.vscroll:isMouseOver() then return false end
        local x,y=s:getMouseX(),s:getMouseY()
        for _,child in ipairs(s.parent.widgets)do
            if (child.isButton or child.roomSlider) and child:getIsVisible()
                and x>=child.x and x<child.x+child.width and y>=child.y and y<child.y+child.height then return false end
        end
        s.roomScrollDrag=true;s:setCapture(true);return true
    end
    p.onMouseMove=function(s,dx,dy)if s.roomScrollDrag then scroll(s,dy);return true end end
    p.onMouseMoveOutside=p.onMouseMove
    p.onMouseUp=function(s)
        if not s.roomScrollDrag then return false end
        s.roomScrollDrag=false;s:setCapture(false);return true
    end
    p.onMouseUpOutside=p.onMouseUp
    p.prerender=function(s)
        if s.parent.tab~="skins" then frosted(s,s.parent,0,-s:getYScroll(),s.width,s.height,.73) end
        s:setStencilRect(0,0,s.width,s.height)
    end
    p.render=function(s) s:clearStencilRect() end
    parent:addChild(p);return p
end
function W:createChildren()
    ISCollapsableWindow.createChildren(self)
    self.fullHeight=self.height;self.roomPage="appearance"
    self.closeButton:setX(18);self.closeButton:setY(6)
    self.closeButton:setWidth(42);self.closeButton:setHeight(38)
    self.closeButton.onclick=function(w)w:back()end
    self.closeButton.prerender=function(b)surface(b,0,0,b.width,b.height,b.mouseOver and .99 or .86)end
    self.closeButton.render=function(b)chevron(b,b.width/2,b.height/2,"left",28,1)end
    self.collapseButton:setVisible(false);self.pinButton:setVisible(false);self.infoButton:setVisible(false)
    self.preview=ISPanel:new(math.floor(self.width*.5-390),50,780,self.height-100);self.preview:initialise();self.preview.background=false
    self.preview.render=function(p)
        local w=p.parent;local ok=false
        local character=w.byCharacter and w.byCharacter[w.character]
        if w.roomPage=="appearance" and character and character.locked then
            local y=math.floor(p.height*.47)
            surface(p,math.floor(p.width*.13),y,math.floor(p.width*.74),62,.91)
            lock(p,math.floor(p.width*.18),y+21)
            label(p,tr("CharacterPackMissing"),p.width*.53,y+11,19,p.width*.58,.97,.97,.94,1,true)
            label(p,tr("CharacterPackWorkshop"),p.width*.53,y+36,15,p.width*.58,.79,.85,.84,1,true)
            return
        end
        local x,y=math.floor(p:getAbsoluteX()),math.floor(p:getAbsoluteY())
        local width,height=math.floor(p.width),math.floor(p.height)
        local now=getTimestampMs()
        local locked=w.roomPage=="appearance" and character and character.unlockRequired==true
        if w.roomPage=="appearance" then
            if w.browsed and now>=w.previewAt then
                ok=AimohPMXReadyRoom.drawBrowseCamera(w.player,w.browsed.id,x,y,width,height,w.yaw,w.zoom,w.cameraPan,w.cameraFocus,locked==true)
            end
        else
            ok=AimohPMXReadyRoom.drawCamera(w.player,x,y,width,height,w.yaw,w.zoom,w.cameraPan,w.cameraFocus)
        end
        if ok and locked then
            local glyph=image(art.."lock-large.png")
            if glyph then p:drawTextureScaled(glyph,p.width/2-58,p.height*.51-58,116,116,.97,1,1,1) end
        end
        if not ok then
            local loading=w.waiting or (w.roomPage=="appearance" and w.browsed and now<w.previewAt+650)
            label(p,tr(loading and "Loading" or "NoPreview"),p.width/2,p.height/2,17,p.width-30,.83,.87,.85,1,true)
        end
    end
    self.preview.onMouseDown=function(p)
        local character=p.parent.byCharacter and p.parent.byCharacter[p.parent.character]
        if character and character.locked then return true end
        p.dragging=true;p.parent.cameraTransition=nil;p:setCapture(true);return true
    end
    self.preview.onMouseMove=function(p,dx,dy)
        if not p.dragging then return false end
        local w=p.parent
        w.yaw=w.yaw+dx*.008
        w.cameraPan=clamp(w.cameraPan+dy/(p.height*w.zoom),-.8,.8)
        return true
    end
    self.preview.onMouseMoveOutside=self.preview.onMouseMove
    self.preview.onMouseUp=function(p)p.dragging=false;p:setCapture(false);return true end
    self.preview.onMouseUpOutside=self.preview.onMouseUp
    self.preview.onMouseWheel=function(p,d)
        local w=p.parent
        w.cameraTransition=nil
        w.zoom=clamp(w.zoom*(d>0 and .89 or d<0 and 1.12 or 1),.62,4.2)
        return true
    end
    self:addChild(self.preview)
    self.workshopButton=button(self.preview,0,0,256,tr("RoomWorkshopOpen"),self,function(w)w:requestWorkshopPage()end)
    self.workshopButton:setHeight(43);self.workshopButton.roomLook="primary"
    self.workshopButton:setVisible(false)
    self.railWidth=115;self.gridWidth=math.min(365,math.floor(self.width*.255))
    self.rightWidth=math.max(270,math.floor(self.width*.30));self.rightX=self.width-self.rightWidth-16
    self.overviewX=math.floor(self.width*.77);self.overviewWidth=math.floor(self.width*.193)
    self.overviewY=math.floor(self.height*.157)
    local railY=math.floor(self.height*.105)
    self.sidebar=ISScrollingListBox:new(math.floor(self.width*.028),railY,self.railWidth,math.floor(self.height*.80));self.sidebar:initialise()
    self.sidebar.itemheight=98;self.sidebar.backgroundColor={r=.025,g=.045,b=.055,a=0};self.sidebar.borderColor.a=0
    self.sidebar:setOnMouseDownFunction(self,function(w,row)
        w:onCharacter(w.rosterExpanded and row[1] or row)
    end)
    self.sidebar.doDrawItem=self.drawCharacter
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
        if s.roomDown and s.roomDrag<=5 then
            local row=s:rowAt(x,y)
            local item=s.items[row]
            if item then
                local w=s.parent
                local column=math.floor((x-4)/math.floor((w.gridWidth-12)/3))+1
                local character=w.rosterExpanded and item.item[column] or nil
                if not w.rosterExpanded then character=item.item end
                if character then s.selected=row;w:onCharacter(character) end
            end
        end
        s.roomDown=false;s:setCapture(false);ISScrollingListBox.onMouseUp(s,x,y);return true
    end
    self.sidebar.onMouseUpOutside=function(s,x,y) s.roomDown=false;s:setCapture(false);ISScrollingListBox.onMouseUpOutside(s,x,y) end
    self:addChild(self.sidebar)
    self.rosterToggle=button(self,self.sidebar.x+self.sidebar.width+8,math.floor(self.height*.465),36,"",self,self.toggleRoster)
    self.rosterToggle:setHeight(47)
    self.rosterToggle.roomArrow="right"
    self.rosterToggle.roomLook="navigation"
    self.rosterToggle:setSound("activate","R116RoomPage")
    self.content=panel(self,self.rightX,105,self.rightWidth,self.height-247)
    self.pageButton=button(self,0,0,136,tr("RoomPartSwitch"),self,function(w)w:setTab("parts")end)
    self.pageButton.roomLook="parts"
    self.chestButton=button(self,0,0,86,tr("ChestPhysics"),self,function(w)w:setTab("chest")end)
    self.gripButton=button(self,0,0,86,tr("GripCalibration"),self,function(w)w:setTab("grip")end)
    self.expressionButton=button(self,0,0,86,tr("Expression"),self,function(w)w:setTab("expression")end)
    self:createExpressionFooter()
    self.skinButton=button(self,0,0,44,"",self,function(w)w:setTab("skins")end)
    self.skinButton.roomLook="outfit"
    self.skinButton:setTooltip(tr("RoomOutfits"))
    self.skinButton:setSound("activate","R116RoomPage")
    self.wearButton=button(self,0,0,160,tr("EnableCharacter"),self,function(w)w:applyBrowse()end)
    self.wearButton.roomLook="primary"
    self.wearButton:setSound("activate","R116RoomConfirm")
    local renderWear=self.wearButton.render
    self.wearButton.render=function(b)
        if b.roomLook~="outfitPurchase" then return renderWear(b) end
        local x=(b.width-33-#tostring(economy.outfitPrice)*23*.63)/2
        local icon=image(art.."credits.png")
        if icon then b:drawTextureScaled(icon,x,7,29,29,1,1,1,1) end
        local affordable=economy.balance(self.player,"credits")>=economy.outfitPrice
        number(b,economy.outfitPrice,x+33,10,23,affordable and .09 or .88,affordable and .15 or .24,.12)
    end
    self.physicsResetButton=button(self,0,0,86,tr("RoomPhysicsShort"),self,self.requestPhysicsReset)
    self.physicsResetButton:setTooltip(tr("PhysicsReset"))
    self.restoreButton=button(self,0,0,96,tr("Restore"),self,self.onRestore)
    self.motionTab=button(self,0,0,86,tr("RoomMotion"),self,self.requestMotionPage)
    self.foldButton=button(self,self.width-53,7,34,"",self,function(w)w:toggleRoomFold()end)
    self.foldButton.roomFold=true;self.foldButton.roomLook="bare"
    self.foldButton:setTooltip(tr("RoomFold"))
    self:createMotionPage()
    self:createRoomResources()
    self.widgets={};self.tab="overview";self.previewAt=0;self:roomVisibility();self:refresh()
end

local resourceTitles={targeted="RoomTargetedPermit",outfit="RoomOutfitPermit",credits="RoomCredits"}

local function resourceIcon(p,key,x,y,size)
    local t=image(art..key..".png")
    if t then p:drawTextureScaled(t,x,y,size,size,1,1,1,1) end
end

function W:createRoomResources()
    self.resourceButtons={}
    local width=math.min(130,math.floor(self.width*.105))
    local start=self.width-60-3*(width+9)
    for i,key in ipairs(economy.keys)do
        local chip=ISPanel:new(start+(i-1)*(width+9),8,width,34)
        chip:initialise();chip.background=false
        chip.prerender=function(p)
            surface(p,0,0,p.width,p.height,.89)
            resourceIcon(p,key,2,1,32)
            local balance=economy.balance(self.player,key)
            local count=#string.format("%.0f",balance)
            local size=math.min(17,(p.width-62)/(count*.63))
            number(p,balance,p.width-29,(p.height-size)/2,size,.95,.96,.95,true)
        end
        self:addChild(chip)
        local add=button(chip,width-26,0,26,"+",self,function(w)w:openExchange(key)end)
        add:setHeight(34);add:setTooltip(tr(resourceTitles[key]))
        add.render=function(b)
            b:drawRect(6,15,14,4,1,.94,.95,.95)
            b:drawRect(11,10,4,14,1,.94,.95,.95)
        end
        self.resourceButtons[i]=chip
    end
    self.drawButtons={}
    for i,key in ipairs({"RoomDrawOnce","RoomDrawTen"})do
        local count=i==1 and 1 or 10
        local width=math.floor(self.width*.137)
        local right=math.floor(self.width*.963)
        local x=right-width*2-19+(i-1)*(width+19)
        local b=button(self,x,math.floor(self.height*.892),width,tr(key),self,function(w)
            if w.tab=="outfitDraw" then w:requestOutfitDraw(count) else w:requestCharacterDraw(count) end
        end)
        b:setHeight(43);b.roomLook=i==1 and "drawOnce" or "drawTen"
        b:setSound("activate","R116RoomDrawStart")
        local draw=b.render
        b.render=function(p)
            draw(p)
            surface(p,0,-29,p.width,25,.79)
            resourceIcon(p,p.roomOutfitDraw and "outfit" or "targeted",p.width/2-34,-32,29)
            label(p,"×",p.width/2-4,-24,15,12)
            number(p,count,p.width/2+9,-24,15,.96,.96,.95)
        end
        b:setEnable(false);b:setVisible(false)
        self.drawButtons[i]=b
    end
end

local drawStep,missLife,hitLife=215,720,1150

function W:requestCharacterDraw(count)
    local character=self.byCharacter[self.character]
    local card=character and data.roomRecruitment[character.name]
    if self.drawAnimation or self.exchange or not card or character.locked
        or not character.unlockRequired or self.tab~="overview" then return end
    local misses=economy.characterMisses(self.player)
    local status,results=economy.drawCharacter(self.player,card.pool,count)
    if status=="short" then self.drawNotice=tr("RoomDrawShort");return end
    if not results then return end
    self.drawNotice=nil
    self.drawAnimation={started=getTimestampMs()+140,results=results,won=status=="unlocked",
        character=character.name,portrait=character.portrait,misses=misses,button=count==1 and 1 or 2}
    self:layoutOverview()
end

function W:requestOutfitDraw(count)
    local character=self.byCharacter[self.character]
    local entry=self.browsed
    if self.drawAnimation or self.exchange or self.tab~="outfitDraw" or not character or character.unlockRequired
        or not entry or not entry.outfitLocked or not entry.outfitPool or entry.id==self.currentId then return end
    local misses=economy.outfitMisses(self.player,entry.id)
    local status,results=economy.drawOutfit(self.player,entry.id,count)
    if status=="short" then self.drawNotice=tr("RoomOutfitDrawShort");return end
    if not results then return end
    self.drawNotice=nil
    self.drawAnimation={started=getTimestampMs()+140,results=results,won=status=="unlocked",outfit=entry.id,
        portrait=entry.meta.portrait,misses=misses,button=count==1 and 1 or 2}
    self:layoutOverview()
end

function W:updateDrawAnimation()
    local a=self.drawAnimation
    if not a then return end
    local elapsed=getTimestampMs()-a.started
    local last=(#a.results-1)*drawStep
    local slot=math.min(#a.results,1+math.floor(elapsed/drawStep))
    if slot>=1 and slot~=a.soundIndex then
        a.soundIndex=slot
        roomSound(a.results[slot] and "DrawHit" or "DrawTick")
    end
    if elapsed>=last+(a.won and hitLife or missLife) then
        self.drawAnimation=nil
        if a.won then
            if a.outfit then self.tab="skins" end
            self:refresh()
            self.toolNotice=tr(a.outfit and "RoomOutfitAcquired" or "RoomDrawUnlocked")
        else
            self.drawNotice=tr("RoomDrawMiss")
            self:layoutOverview()
        end
    end
end

function W:drawRecruitmentResults()
    local a=self.drawAnimation
    if not a then return end
    local elapsed=getTimestampMs()-a.started
    local button=self.drawButtons[a.button]
    local x=button.x+(button.width-142)/2
    local portrait=image(a.portrait)
    for i,hit in ipairs(a.results)do
        local age=elapsed-(i-1)*drawStep
        local life=hit and hitLife or missLife
        if age>=0 and age<life then
            local rise=hit and 119*ease(age/520) or 185*age/life
            local y=button.y-47-rise
            local alpha=clamp(age/80,0,1)*clamp((life-age)/190,0,1)
            if hit then
                self:drawRect(x,y,142,47,.94*alpha,.27,.18,.04)
                self:drawRectBorder(x-1,y-1,144,49,alpha,.99,.72,.22)
                self:drawRectBorder(x+1,y+1,140,45,alpha,.96,.61,.12)
                if portrait then self:drawTextureScaled(portrait,x+5,y+4,40,40,alpha,1,.96,.86) end
                label(self,tr("RoomDrawHit"),x+52,y+13,19,84,1,.85,.46,alpha)
            else
                self:drawRect(x,y,142,47,.91*alpha,.05,.07,.08)
                self:drawRectBorder(x,y,142,47,.46*alpha,.28,.32,.33)
                if portrait then self:drawTextureScaled(portrait,x+5,y+4,40,40,alpha,.11,.12,.13) end
                label(self,tr("RoomDrawMiss"),x+52,y+14,16,84,.72,.76,.77,alpha)
            end
        end
    end
end

function W:closeExchange()
    local exchange=self.exchange
    if not exchange then return end
    self.exchange=nil
    exchange.slider.dragInside=false;exchange.slider:setCapture(false)
    self:removeChild(exchange.overlay)
end

function W:closeWorkshopPrompt()
    local prompt=self.workshopPrompt
    if not prompt then return end
    self.workshopPrompt=nil
    self:removeChild(prompt.overlay)
end

function W:requestWorkshopPage()
    local character=self.byCharacter and self.byCharacter[self.character]
    if self.closed or self.folded or self.foldStart or self.exchange or self.workshopPrompt
        or self.roomPage~="appearance" or self.tab~="overview" or not character or not character.locked then return end
    local id=(data.roomWorkshopItems or {})[character.name]
    if type(id)~="string" or not id:match("^%d+$") then return end
    local name=character.name
    self.preview.dragging=false;self.preview:setCapture(false)
    local overlay=ISPanel:new(0,0,self.width,self.height)
    overlay:initialise();overlay.background=false
    overlay.prerender=function(p)p:drawRect(0,0,p.width,p.height,.72,.04,.07,.08)end
    overlay.onMouseDown=function()return true end
    overlay.onMouseUp=overlay.onMouseDown;overlay.onMouseWheel=overlay.onMouseDown
    local width=math.min(560,self.width-80)
    local card=ISPanel:new(math.floor((self.width-width)/2),math.floor((self.height-240)/2),width,240)
    card:initialise();card.background=false;overlay:addChild(card)
    card.prerender=function(p)
        surface(p,0,0,p.width,p.height,.98)
        label(p,tr("RoomWorkshopConfirm"),p.width/2,28,26,p.width-48,.96,.96,.94,1,true)
        label(p,name,p.width/2,91,31,p.width-48,.95,.81,.51,1,true)
        label(p,tr("RoomWorkshopDestination"),p.width/2,137,16,p.width-48,.80,.85,.86,1,true)
    end
    local prompt={overlay=overlay,card=card,id=id,name=name}
    local buttonWidth=math.floor((width-72)/2)
    prompt.cancel=button(card,24,184,buttonWidth,tr("RoomExchangeCancel"),self,function(w)w:closeWorkshopPrompt()end)
    prompt.confirm=button(card,48+buttonWidth,184,buttonWidth,tr("RoomWorkshopAccept"),self,function(w)
        if w.workshopPrompt~=prompt then return end
        w:closeWorkshopPrompt()
        if isSteamOverlayEnabled() then activateSteamOverlayToWorkshopItem(id)
        else openUrl("https://steamcommunity.com/sharedfiles/filedetails/?id="..id) end
    end)
    prompt.cancel:setHeight(36);prompt.confirm:setHeight(36);prompt.confirm.roomLook="primary"
    self.workshopPrompt=prompt
    self:addChild(overlay)
end

function W:updateExchange(quantity)
    local s=self.exchange
    s.maximum=economy.available(self.player,s.key)
    s.quantity=clamp(math.floor(quantity+.5),1,math.max(1,s.maximum))
    s.slider:setValues(1,math.max(2,s.maximum),1,10,true)
    s.slider:setCurrentValue(s.quantity,true)
    s.slider.disabled=s.maximum<=1
    s.less:setEnable(s.quantity>1);s.minimum:setEnable(s.quantity>1)
    s.more:setEnable(s.quantity<s.maximum);s.maximumButton:setEnable(s.quantity<s.maximum)
    s.confirm:setEnable(s.maximum>0)
end

function W:openExchange(key)
    if self.folded or self.foldStart or self.drawAnimation then return end
    self:closeExchange()
    local overlay=ISPanel:new(0,0,self.width,self.height);overlay:initialise();overlay.background=false
    overlay.prerender=function(p)p:drawRect(0,0,p.width,p.height,.72,.05,.09,.10)end
    overlay.onMouseDown=function()return true end
    overlay.onMouseUp=overlay.onMouseDown
    overlay.onMouseWheel=overlay.onMouseDown
    local width=math.min(810,math.floor(self.width*.78))
    local height=math.min(433,self.height-76)
    local card=ISPanel:new(math.floor((self.width-width)/2),math.floor((self.height-height)/2),width,height)
    card:initialise();card.background=false;overlay:addChild(card)
    local s={key=key,overlay=overlay,quantity=1}
    local buyingCredits=key=="credits"
    local cost=buyingCredits and 1 or economy.cost[key]
    local rateLabel=buyingCredits and tr("RoomCashRate") or tr(key=="targeted" and "RoomTargetedPermitRate" or "RoomPermitRate")
    card.prerender=function(p)
        p:drawRect(0,0,p.width,p.height,.98,.86,.89,.89)
        p:drawRect(0,0,p.width,5,.96,.27,.40,.42)
        p:drawRect(29,44,144,144,.34,.46,.59,.61)
        resourceIcon(p,key,39,53,124)
        label(p,tr(resourceTitles[key]),198,45,29,p.width-280,.07,.13,.15)
        p:drawRect(198,84,p.width-239,1,.41,.28,.40,.43)
        label(p,rateLabel,198,94,17,p.width-239,.23,.35,.38)
        label(p,tr("RoomExchangeQuantity"),p.width/2-90,p.height-154,17,124,.13,.22,.24)
        number(p,s.quantity,p.width/2+40,p.height-154,17,.14,.27,.31)
        label(p,tr("RoomExchangeSpend"),p.width/2-100,p.height-76,17,80,.19,.34,.39)
        if not buyingCredits then resourceIcon(p,"credits",p.width/2-26,p.height-83,27) end
        number(p,s.quantity*cost,p.width/2+5,p.height-76,17,.14,.27,.31)
        label(p,tr(buyingCredits and "RoomCash" or "RoomCredits"),p.width/2+90,p.height-76,16,78,.19,.34,.39)
        if buyingCredits then
            label(p,tr("RoomCredits"),198,133,16,85,.19,.34,.39)
            number(p,s.quantity*economy.creditsPerCash,290,130,22,.12,.29,.35)
        end
        local message=s.message or (s.maximum==0 and tr("RoomNotEnough"))
        if message then label(p,message,p.width/2,p.height-107,15,p.width-50,.59,.24,.15,1,true) end
    end
    local left=math.floor(width*.15)
    local right=math.floor(width*.85)
    s.minimum=button(card,left-31,height-128,60,tr("RoomExchangeMin"),self,function(w)w:updateExchange(1)end)
    s.less=button(card,left+35,height-128,33,"-",self,function(w)w:updateExchange(s.quantity-1)end)
    s.more=button(card,right-68,height-128,33,"+",self,function(w)w:updateExchange(s.quantity+1)end)
    s.maximumButton=button(card,right-29,height-128,60,tr("RoomExchangeMax"),self,function(w)w:updateExchange(s.maximum)end)
    s.slider=ISSliderPanel:new(left+77,height-126,right-left-154,23,self,function(w,value)w:updateExchange(value)end)
    s.slider:initialise();s.slider:instantiate();s.slider:setDoButtons(false);card:addChild(s.slider)
    button(card,width-54,14,34,"X",self,function(w)w:closeExchange()end)
    button(card,math.floor(width*.23),height-48,math.floor(width*.24),tr("RoomExchangeCancel"),self,function(w)w:closeExchange()end)
    s.confirm=button(card,math.floor(width*.53),height-48,math.floor(width*.24),tr("RoomExchangeBuy"),self,function(w)
        if economy.buy(w.player,key,s.quantity) then
            s.message=tr("RoomExchangeDone")
        else s.message=tr("RoomNotEnough") end
        w:updateExchange(s.quantity)
    end)
    s.confirm:setSound("activate","R116RoomConfirm")
    self.exchange=s
    self:addChild(overlay)
    self:updateExchange(1)
end

function W:createExpressionFooter()
    local footer=ISPanel:new(self.rightX,self.fullHeight-116,self.rightWidth,72)
    footer:initialise();footer.background=false
    footer.prerender=function(p)surface(p,0,0,p.width,p.height,.97)end
    self:addChild(footer);self.expressionFooter=footer
    self.expressionSave=button(footer,4,4,footer.width-8,tr("ExpressionSave"),self,function(w)
        w.expressionSaving=not w.expressionSaving;w:refreshExpressionSlots(true)
    end)
    self.expressionSlotButtons={}
    local width=(footer.width-24)/5
    for index=1,5 do
        local slot=index
        local x=4+(index-1)*(width+4)
        self.expressionSlotButtons[index]=button(footer,x,36,width,tostring(index),self,function(w)w:useExpressionSlot(slot)end)
    end
    footer:setVisible(false)
end

function W:expressionSlots()
    local slots=self.player:getModData().AimohPMXExpressions
    return slots and slots[self.currentId] or {}
end

function W:refreshExpressionSlots(visible)
    self.expressionFooter:setVisible(visible)
    if not visible then self.expressionSaving=false;return end
    self.expressionSave.roomTitle=tr(self.expressionSaving and "ExpressionPickSlot" or "ExpressionSave")
    self.expressionSave.roomActive=self.expressionSaving
    local slots=self:expressionSlots()
    for index,b in ipairs(self.expressionSlotButtons)do
        local saved=type(slots[tostring(index)])=="string"
        b:setEnable(saved or self.expressionSaving==true)
        b.roomActive=saved
        b.backgroundColor=saved and {r=.25,g=.17,b=.07,a=.98} or {r=.06,g=.08,b=.10,a=.70}
    end
end

function W:useExpressionSlot(index)
    if self.tab~="expression" or self.roomPage~="appearance" or not self:canEditCurrent() then return end
    local slot=tostring(index)
    local slots=self:expressionSlots()
    if self.expressionSaving then
        local snapshot=AimohPMXReadyRoom.expressionSnapshot(self.player,self.currentId)
        if snapshot=="" then self.status=tr("Error");return end
        slots[slot]=snapshot
        local saved=self.player:getModData()
        saved.AimohPMXExpressions=saved.AimohPMXExpressions or {}
        saved.AimohPMXExpressions[self.currentId]=slots
        self.expressionSaving=false;self:refreshExpressionSlots(true)
    elseif type(slots[slot])=="string" then
        local error=AimohPMXReadyRoom.expressionLoad(self.player,self.currentId,slots[slot])
        if error~="" then print("[AimohPMXPreview] "..error);self.status=tr("Error");return end
        self:refreshControls(true)
    else return end
    self.actions.expressionRemember(self.player)
end

function W:createMotionPage()
    local height=self.fullHeight-68
    self.motionWidth=math.min(250,math.floor(self.width*.26))
    local library=ISPanel:new(14,54,self.motionWidth,height)
    library:initialise();library.background=false;self:addChild(library);self.motionLibraryBox=library
    library.prerender=function(p)
        surface(p,0,0,p.width,p.height,.95)
        label(p,tr("MotionLibrary"),12,12,22,p.width-24)
        if #p.parent.motionList.items==0 then
            label(p,tr("MotionNoFiles"),12,54,18,p.width-24,.62,.73,.75)
        end
    end
    local list=ISScrollingListBox:new(8,43,library.width-16,height-123)
    list:initialise();list.itemheight=42
    list.backgroundColor={r=.035,g=.055,b=.065,a=0}
    list.doDrawItem=function(s,y,row,alt)
        if row.index==s.selected then
            s:drawRect(0,y,s.width-14,row.height-2,.93,.15,.19,.20)
            s:drawRect(0,y+4,2,row.height-10,1,1,.71,.38)
        elseif s.mouseoverSelected==row.index then s:drawRect(0,y,s.width-14,row.height-2,.60,.13,.20,.24)
        elseif alt then s:drawRect(0,y,s.width-14,row.height-2,.42,.055,.085,.105) end
        label(s,row.text,9,y+5,17,s.width-28)
        label(s,row.source,9,y+24,13,s.width-28,.58,.70,.72)
        return y+row.height
    end
    list:setOnMouseDownFunction(self,function(w,filename)w.motionSelected=filename;w.motionMessage=nil;w:refreshMotionControls()end)
    library:addChild(list);self.motionList=list
    local width=library.width-16
    local half=math.floor((width-6)/2)
    self.motionAddPath=button(library,8,height-70,width,tr("MotionAddPath"),self,self.onAddMotionPath)
    self.motionRefresh=button(library,8,height-34,half,tr("MotionRefresh"),self,self.refreshMotionLibrary)
    self.motionRemovePath=button(library,14+half,height-34,width-half-6,tr("MotionRemovePath"),self,self.onRemoveMotionPath)

    local box=ISPanel:new(self.rightX,54,self.rightWidth,height)
    box:initialise();box.background=false;self:addChild(box);self.motionBox=box
    box.prerender=function(p)
        local w=p.parent
        local textWidth=p.width-24
        surface(p,0,0,p.width,p.height,.95)
        label(p,tr("MotionControls"),12,12,22,textWidth)
        local state=w.motionPlayback
        if state and state.name~="" then
            local frame=state.frame or 0
            for i=0,2 do
                local h=4+math.floor(5*(.5+.5*math.sin(frame*.24+i*1.3)))
                p:drawRect(12+i*4,56-h,2,h,1,1,.71,.38)
            end
        end
        label(p,w.motionStatus or tr("MotionStopped"),28,40,17,textWidth-16,.69,.86,.88)
        if w.motionPlayingLabel then label(p,w.motionPlayingLabel,12,60,17,textWidth) end
        label(p,tr("MotionSpeed"),12,118,15,textWidth,.65,.73,.77)
        p:drawTextRight(string.format("%.2f×",w.motionSpeed.currentValue),p.width-12,117,.82,.88,.90,1,UIFont.Small)
        p:drawRect(12,162,textWidth,1,.28,.41,.55,.60)
        label(p,tr("MotionSelected"),12,171,14,textWidth,.65,.73,.77)
        label(p,w.motionSelectedLabel or tr("MotionNoSelection"),12,189,16,textWidth)
        p:drawRect(12,284,textWidth,1,.28,.41,.55,.60)
        label(p,tr("MotionPhysics"),12,293,16,textWidth,.65,.73,.77)
        if w.motionMessage then
            label(p,fitMotionLine(w.motionMessage,textWidth),12,348,17,textWidth,1,.74,.42)
        end
    end
    width=box.width-24
    local third=math.floor((width-12)/3)
    half=math.floor((width-6)/2)
    local function control(x,y,w,key,command)
        return button(box,x,y,w,tr(key),self,
            function(w)w:onMotionCommand(command)end)
    end
    self.motionButtons={
        play=control(12,211,width,"MotionPlay","play"),
        pause=control(12,245,third,"MotionPause","pause"),
        restart=control(18+third,245,third,"MotionRestart","restart"),
        stop=control(24+2*third,245,width-2*third-12,"MotionStop","stop"),
        physics=control(12,316,half,"MotionPhysicsOn","physics"),
        reset=control(18+half,316,width-half-6,"MotionPhysicsReset","reset"),
    }
    self.motionButtons.play.backgroundColor={r=.23,g=.18,b=.12,a=.97}
    self.motionButtons.play.borderColor={r=1,g=.73,b=.42,a=.95}
    self.motionButtons.play.roomActive=true
    for _,key in ipairs({"play","pause","restart","stop"})do self.motionButtons[key].roomIcon=key end
    self:createMotionTimeline(box,width)
    local speed=ISSliderPanel:new(12,135,width,21,self,function(w,value)
        if math.abs(value-(w.motionPlayback.speed or 1))<.0001 then return end
        w:onMotionCommand("speed",tostring(value))
    end)
    speed:initialise();speed:instantiate()
    speed:setDoButtons(false);speed:setValues(0,1,.01,.1,true)
    speed.currentValue=1;speed.disabled=true
    box:addChild(speed);self.motionSpeed=speed
end

local function clockText(frame)
    local seconds=math.floor(frame/30)
    return string.format("%02d:%02d",math.floor(seconds/60),seconds%60)
end

function W:createMotionTimeline(box,width)
    local slider=ISSliderPanel:new(12,81,width,34,self)
    slider:initialise();slider:instantiate()
    slider:setDoButtons(false);slider:setValues(0,1,.00001,.01,true)
    slider.currentValue=0;slider.disabled=true
    slider:paginate()
    slider.sliderBarDim.x=4;slider.sliderBarDim.w=width-8
    local mouseDown=slider.onMouseDown
    slider.cancel=function(s)
        if not s.dragInside then return end
        s.dragInside=false;s.seekRevision=nil;s:setCapture(false)
    end
    slider.onMouseDown=function(s,x,y)
        if s.disabled or y<13 then return false end
        mouseDown(s,x,y)
        if not s.dragInside then return false end
        s.seekRevision=s.target.motionPlayback.revision
        s:setCapture(true)
        return true
    end
    slider.onMouseUp=function(s,x)
        if not s.dragInside then return false end
        local revision=s.seekRevision
        local fraction=clamp((x-s.sliderBarDim.x)/s.sliderBarDim.w,0,1)
        s:cancel()
        local w=s.target
        local error=AimohPMXReadyRoom.motionCommand(w.player,"seek",revision..":"..tostring(fraction))
        w.motionMessage=error~="" and error or nil;w:refreshMotionControls()
        return true
    end
    slider.onMouseUpOutside=slider.onMouseUp
    slider.render=function(s)
        local state=s.target.motionPlayback
        local duration=state and state.endFrame or 0
        local frame=s.dragInside and duration*s.currentValue or state and state.frame or 0
        local startX=s.sliderBarDim.x
        local track=s.sliderBarDim.w
        local fill=track*clamp(s.currentValue,0,1)
        s:drawRect(startX,25,track,3,.92,.15,.21,.24)
        if not s.disabled then
            if fill>0 then s:drawRect(startX,25,fill,3,1,1,.71,.38) end
            local knob=image(art.."knob.png")
            if knob then s:drawTextureScaled(knob,startX+fill-4,22,8,8,1,1,.79,.51) end
        end
        local current=math.floor(frame/30)
        local total=math.floor(duration/30)
        if current~=s.lastSecond then s.lastSecond=current;s.clock=clockText(frame) end
        if total~=s.lastDuration then s.lastDuration=total;s.total=clockText(duration) end
        s:drawText(s.disabled and "--:--" or s.clock,0,0,.82,.88,.90,1,UIFont.Small)
        s:drawTextRight(s.disabled and "--:--" or s.total,s.width,0,.58,.67,.71,1,UIFont.Small)
    end
    box:addChild(slider);self.motionSeek=slider
end

function W:refreshMotionLibrary()
    local files=AimohPMXReadyRoom.motionLibrary()
    self.motionList:clear()
    local selected=nil
    for i=1,#files do
        local file=files[i]
        local row=self.motionList:addItem(fitMotionLine(motionDisplayName(file),self.motionList.width-28),file,
            isLocalMotion(file) and file or motionDisplayName(file))
        local kind=file:sub(1,7)=="sample:" and "MotionExample" or (isLocalMotion(file) and "MotionLocal" or "MotionFolder")
        row.source=tr(kind)
        if file==self.motionSelected then selected=i end
    end
    self.motionList.selected=selected or (#files>0 and 1 or 0)
    self.motionSelected=files[self.motionList.selected]
    self.motionMessage=nil;self:refreshMotionControls()
end

function W:closeMotionPathDialog()
    if self.motionPathDialog then
        if self.motionPathDialog:isVisible() then self.motionPathDialog:destroy() end
        self.motionPathDialog=nil
    end
end

function W:openMotionPathDialog(removing)
    if self.motionPathDialog and self.motionPathDialog:isVisible() then return end
    local width=math.min(610,self.width-32)
    local x=self:getAbsoluteX()+math.floor((self.width-width)/2)
    local y=self:getAbsoluteY()+math.floor((self.height-125)/2)
    local selected=self.motionSelected
    local initial=removing and isLocalMotion(selected) and selected:match("^(.*[/\\])[^/\\]+$") or ""
    local title=tr(removing and "MotionRemovePathTitle" or "MotionPathTitle")
    local dialog=ISTextBox:new(x,y,width,125,"",initial,self,function(w,b)
        local box=w.motionPathDialog
        box:showErrorMessage(false)
        if b.internal=="OK" then
            local path=box.entry:getText()
            local error
            if removing then error=AimohPMXReadyRoom.removeMotionPath(path)
            else error=AimohPMXReadyRoom.addMotionPath(path).error end
            if error and error~="" then box:showErrorMessage(true,error);return end
            w:refreshMotionLibrary()
        end
        w.motionPathDialog=nil
    end,self.player:getPlayerNum())
    local drawDialog=dialog.prerender
    dialog.prerender=function(box)
        drawDialog(box)
        label(box,title,12,4,18,box.width-24)
    end
    self.motionPathDialog=dialog
    dialog:initialise();dialog:addToUIManager();dialog.entry:focus()
end

function W:onAddMotionPath()
    self:openMotionPathDialog(false)
end

function W:onRemoveMotionPath()
    self:openMotionPathDialog(true)
end

function W:refreshMotionControls()
    local state=AimohPMXReadyRoom.motionState(self.player)
    self.motionPlayback=state
    local active=state.name~="";local b=self.motionButtons
    local slider=self.motionSeek
    local duration=state.endFrame or 0
    slider.disabled=not active or duration<=0 or self.waiting
    if slider.dragInside and (slider.disabled or slider.seekRevision~=state.revision) then slider:cancel() end
    if not slider.dragInside then slider.currentValue=not slider.disabled and clamp((state.frame or 0)/duration,0,1) or 0 end
    local speed=self.motionSpeed
    speed.disabled=slider.disabled
    if speed.disabled then speed.dragInside=false end
    speed.currentValue=state.speed or 1
    self.motionStatus=tr(active and ((state.paused or state.speed==0) and "MotionPaused" or "MotionPlaying") or "MotionStopped")
    if self.motionPlayingFile~=state.name then
        self.motionPlayingFile=state.name
        self.motionPlayingLabel=active and fitMotionLine(motionDisplayName(state.name),self.motionBox.width-24) or nil
    end
    if self.motionSelectionFile~=self.motionSelected then
        self.motionSelectionFile=self.motionSelected
        self.motionSelectedLabel=self.motionSelected and fitMotionLine(motionDisplayName(self.motionSelected),self.motionBox.width-24) or nil
    end
    b.play:setEnable(self.motionSelected~=nil and not self.waiting and AimohPMXPreview.isEnabled(self.player))
    for _,key in ipairs({"pause","restart","stop","physics","reset"})do b[key]:setEnable(active and not self.waiting)end
    b.reset:setEnable(active and state.physics and not self.waiting)
    b.pause.roomTitle=tr(state.paused and "MotionResume" or "MotionPause")
    b.pause.roomIcon=state.paused and "play" or "pause"
    b.physics.roomTitle=tr(state.physics and "MotionPhysicsOn" or "MotionPhysicsOff")
end

function W:onMotionCommand(command,value)
    local error=AimohPMXReadyRoom.motionCommand(self.player,command,value or self.motionSelected or "")
    self.motionMessage=error~="" and error or nil;self:refreshMotionControls()
end

function W:roomVisibility()
    local expanded=not self.folded and not self.foldStart
    local motions=expanded and self.roomPage=="motions"
    if not motions then self.motionSpeed.dragInside=false end
    local appearance=expanded and not motions
    self.preview:setVisible(expanded)
    if motions then
        self.preview:setX(self.motionWidth+28);self.preview:setWidth(self.rightX-self.preview.x-14)
        self.preview:setY(56);self.preview:setHeight(self.fullHeight-70)
    else
        self.preview:setX(math.floor(self.width*.23));self.preview:setWidth(math.floor(self.width*.54))
        self.preview:setY(math.floor(self.fullHeight*.08));self.preview:setHeight(math.floor(self.fullHeight*.86))
    end
    self:refreshExpressionSlots(false)
    for _,widget in ipairs({self.sidebar,self.rosterToggle,self.content,self.pageButton,self.chestButton,self.gripButton,
        self.expressionButton,self.skinButton,self.wearButton,self.physicsResetButton,self.restoreButton,self.motionTab,self.workshopButton}) do
        widget:setVisible(false)
    end
    self.motionLibraryBox:setVisible(motions);self.motionBox:setVisible(motions)
    for _,b in ipairs(self.resourceButtons)do b:setVisible(expanded) end
    self.closeButton:setVisible(true)
    if appearance then self:layoutOverview()
    else for _,b in ipairs(self.drawButtons)do b:setVisible(false) end end
end

function W:back()
    if self.workshopPrompt then self:closeWorkshopPrompt();return end
    if self.exchange then self:closeExchange();return end
    if self.drawAnimation then return end
    if self.folded or self.foldStart then self:close()
    elseif self.roomPage=="motions" then self:setRoomPage("appearance")
    elseif self.tab=="outfitDraw" then self:setTab("skins")
    elseif self.tab=="parts" then self:setTab("skins")
    elseif self.tab~="overview" then self:setTab("overview")
    else self:close() end
end

function W:setRoomPage(page)
    if self.roomPage==page or self.folded or self.foldStart then return end
    self:closeWorkshopPrompt()
    self:closeMotionPathDialog()
    self.motionSeek:cancel()
    self.pageFade=getTimestampMs()
    if page=="motions" then AimohPMXReadyRoom.release(self.player) end
    self:clearContent()
    self.preview.dragging=false;self.preview:setCapture(false)
    self.sidebar.roomDown=false;self.sidebar:setCapture(false)
    self.roomPage=page;self:roomVisibility()
    if page=="motions" then self:refreshMotionLibrary() else self:refreshControls() end
end

function W:toggleRoomFold()
    if self.drawAnimation then return end
    self:closeWorkshopPrompt()
    self:closeExchange()
    self:closeMotionPathDialog()
    self.motionSeek:cancel()
    self.folded=not self.folded
    self.preview.dragging=false;self.preview:setCapture(false)
    self.sidebar.roomDown=false;self.sidebar:setCapture(false)
    if self.folded then
        self:clearContent()
        AimohPMXReadyRoom.release(self.player)
    end
    self.foldFrom=self.height;self.foldStart=getTimestampMs()
    self.foldTo=self.folded and self:titleBarHeight() or self.fullHeight
    self.foldButton:setTooltip(tr(self.folded and "RoomUnfold" or "RoomFold"))
    self:roomVisibility()
end

function W:layoutOverview()
    local overview=self.tab=="overview"
    local skins=self.tab=="skins"
    local character=self.byCharacter and self.byCharacter[self.character]
    local loaded=character~=nil and not character.locked
    local locked=loaded and character.unlockRequired==true
    local available=loaded and not locked
    self.workshopButton:setX(math.floor((self.preview.width-self.workshopButton.width)/2))
    self.workshopButton:setY(math.floor(self.preview.height*.47)+76)
    self.workshopButton:setVisible(not not (overview and character and character.locked))
    local outfitDraw=available and self.tab=="outfitDraw" and self.browsed and self.browsed.outfitLocked
        and self.browsed.outfitPool~=nil
    local characterDraw=overview and locked
    for _,b in ipairs(self.drawButtons)do
        b.roomOutfitDraw=outfitDraw
        b:setVisible(not not (characterDraw or outfitDraw))
        b:setEnable(not self.drawAnimation and not not (outfitDraw
            or (characterDraw and data.roomRecruitment[character.name]~=nil)))
    end
    local x,y,width=self.overviewX,self.overviewY,self.overviewWidth
    local controls={self.chestButton,self.gripButton,self.expressionButton,self.motionTab,self.physicsResetButton}
    local symbols={"chest","grip","expression","play","restart"}
    local tileWidth=math.floor((width-22-14)/3)
    for i,b in ipairs(controls) do
        b.roomTile=symbols[i]
        b:setX(x+11+((i-1)%3)*(tileWidth+7))
        b:setY(y+77+math.floor((i-1)/3)*73)
        b:setWidth(tileWidth);b:setHeight(66)
        b:setVisible(available and overview)
    end
    self.restoreButton:setX(x+10);self.restoreButton:setY(y+226)
    self.restoreButton.roomLook="bare"
    self.restoreButton:setVisible(available and overview and AimohPMXPreview.isEnabled(self.player))
    self.wearButton:setX(x);self.wearButton:setY(math.floor(self.height*.919))
    self.wearButton:setWidth(width);self.wearButton:setHeight(43)
    self.wearButton:setVisible(available and self.tab~="outfitDraw" and (overview or skins or not self:canEditCurrent()))
    self.skinButton:setWidth(46);self.skinButton:setHeight(43)
    self.skinButton:setX(x-58);self.skinButton:setY(self.wearButton.y)
    self.skinButton:setVisible(available and overview)
    self.pageButton:setWidth(136);self.pageButton:setHeight(43)
    self.pageButton:setX(x-150);self.pageButton:setY(self.wearButton.y)
    self.pageButton:setVisible(available and skins)
    self.sidebar:setVisible(overview)
    self.rosterToggle:setVisible(overview)
end

function W:rebuildRoster()
    local rail=self.sidebar
    rail:clear();rail.smoothScrollTargetY=nil
    rail.itemheight=self.rosterExpanded and math.floor((self.gridWidth-12)/3)+20 or math.max(95,math.floor(self.fullHeight*.108))
    local selected
    if self.rosterExpanded then
        for _,missing in ipairs({false,true}) do
            local row={};local header
            for _,character in ipairs(self.characters) do
                if character.locked==missing then
                    if not header then
                        header=rail:addItem("",{header=tr(missing and "RoomRosterMissing" or "RoomRoster")})
                        header.height=32
                    end
                    if #row==0 then rail:addItem("",row) end
                    table.insert(row,character)
                    if character.name==self.character then selected=#rail.items end
                    if #row==3 then row={} end
                end
            end
        end
    else
        for _,character in ipairs(self.characters) do
            rail:addItem(character.name,character)
            if character.name==self.character then selected=#rail.items end
        end
    end
    rail:setYScroll(0)
    if selected then rail.selected=selected;rail:ensureVisible(selected) end
end

function W:toggleRoster()
    self.rosterExpanded=not self.rosterExpanded
    self.railFrom=self.sidebar.width
    self.railStart=getTimestampMs()
    self.rosterToggle.roomArrow=self.rosterExpanded and "left" or "right"
    self:rebuildRoster()
end

function W:drawCharacter(y,item,alt)
    local window=self.parent
    if window.rosterExpanded then
        if item.item.header then
            self:drawRect(4,y+6,1,17,.60,.70,.74,.72)
            label(self,item.item.header,14,y+4,18,self.width-20,.76,.77,.76)
            return y+item.height
        end
        local cell=math.floor((window.gridWidth-12)/3)
        for i,entry in ipairs(item.item) do
            local x=4+(i-1)*cell
            local size=cell-7
            local selected=window.character==entry.name
            local texture=image(entry.portrait)
            self:drawRect(x,y+3,size,size,.96,.76,.76,.79)
            if texture then self:drawTextureScaled(texture,x,y+3,size,size,1,1,1,1) end
            self:drawRect(x,y+size+2,size,18,.96,.110,.149,.169)
            self:drawRect(x,y+size,size,2,.93,.875,.620,0)
            label(self,entry.name,x+size/2,y+size+4,12,size-6,.94,.94,.93,1,true)
            if entry.locked or entry.unlockRequired then self:drawRect(x,y+3,size,size,.54,.05,.06,.06);lock(self,x+size-23,y+size-20) end
            if selected then
                self:drawRectBorder(x-1,y+2,size+2,size+20,1,.99,.42,.11)
                self:drawRectBorder(x,y+3,size,size+18,1,.99,.49,.15)
            end
        end
    else
        local entry=item.item
        local selected=window.character==entry.name
        local avatar=entry.portrait and entry.portrait:gsub("/round%-","/avatar-")
        local t=image(avatar) or image(entry.portrait)
        local shade=(entry.locked or entry.unlockRequired) and .47 or 1
        local cx,cy=55,y+item.height/2
        local size=selected and 88 or 72
        if t then self:drawTextureScaled(t,cx-size/2,cy-size/2,size,size,1,shade,shade,shade) end
        local ring=image(data.portraitRing)
        if ring then
            if selected then
                self:drawTextureScaled(ring,cx-51,cy-51,102,102,.17,.40,.82,.81)
                self:drawTextureScaled(ring,cx-46,cy-46,92,92,.99,.92,.93,.92)
            else self:drawTextureScaled(ring,cx-38,cy-38,76,76,.71,.30,.32,.32) end
        end
        if selected then
            local phase=getTimestampMs()/475
            for i=0,4 do
                local a=phase-i*.083
                self:drawRect(cx+math.cos(a)*45,cy+math.sin(a)*45,2,2,.78-i*.14,.67,.92,.89)
            end
        elseif not entry.locked then
            for i=0,10 do
                local a=(.22+i*.057)*math.pi
                local b=a+.057*math.pi
                self:drawLine2(cx+math.cos(a)*37,cy+math.sin(a)*37,cx+math.cos(b)*37,cy+math.sin(b)*37,.88,.875,.620,0)
            end
        end
        if entry.locked or entry.unlockRequired then lock(self,cx+21,cy+20) end
    end
    return y+item.height
end
function W:clearContent()
    for _,widget in ipairs(self.widgets) do
        widget:setCapture(false)
        if widget.hidePopup then widget:hidePopup() end
        if widget.deactivateToolTip then widget:deactivateToolTip() end
        self.content:removeChild(widget)
    end
    self.widgets={};self.carouselPanel=nil
    if self.content.roomScrollDrag then self.content:onMouseUp() end
    self.content:setYScroll(0)
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
    local function cardHeight(s)return math.min(math.floor(self.fullHeight*.44),s.height-28) end
    for i,e in ipairs(p.entries)do if e.id==(self.browseId or self.currentId)then p.index=i end end
    local function select(s,index,openPool)
        local nextIndex=clamp(index,1,#s.entries)
        if s.index~=nextIndex then
            s.slideFrom=(nextIndex-s.index)*(cardHeight(s)*.505+15)+s.offset
            s.changedAt=getTimestampMs()
            roomSound("Select")
        end
        s.index=nextIndex;s.offset=0
        local entry=s.entries[s.index]
        self:previewEntry(entry);self:updateWear()
        if openPool and entry.outfitPool and entry.outfitLocked and entry.id~=self.currentId then
            self:setTab("outfitDraw")
        end
    end
    p.prerender=function(s)
        s:setStencilRect(0,0,s.width,s.height)
        local height=cardHeight(s);local width=height*.505;local step=width+15
        local function card(i)
            local phase=s.changedAt and ease((getTimestampMs()-s.changedAt)/180) or 1
            local active=i==s.index;local scale=active and (.88+.12*phase) or .88
            local w,h=width*scale,height*scale
            local x=s.width/2-w/2+(i-s.index)*step+s.offset+(s.slideFrom or 0)*(1-phase);local y=(s.height-h)/2
            local meta=s.entries[i].meta
            local t=image(meta.card)
            if t then s:drawTextureScaledAspect(t,x,y,w,h,active and 1 or .61,1,1,1)end
            if active then
                s:drawRectBorder(x,y,w,h,1,.99,.42,.11)
                s:drawRectBorder(x+1,y+1,w-2,h-2,1,.99,.51,.19)
            end
            surface(s,x+3,y+h-38,w-6,34,.62)
            local pool=s.entries[i].outfitPool
            label(s,pool and pool.name or meta.officialSkin or meta.skin,x+w/2,y+h-31,17,w-14,.96,.96,.94,1,true)
            if s.entries[i].outfitLocked and s.entries[i].id~=self.currentId then
                s:drawRect(x,y,w,h,.54,.02,.035,.045);lock(s,x+w/2-10,y+h/2-10)
            end
        end
        for i=1,#s.entries do if i~=s.index then card(i)end end
        card(s.index)
    end
    p.render=function(s)s:clearStencilRect()end
    p.onMouseDown=function(s)s.dragging=true;s.offset=0;s:setCapture(true);return true end
    p.onMouseMove=function(s,dx,dy)if s.dragging then s.offset=s.offset+dx end;return true end
    p.onMouseMoveOutside=p.onMouseMove
    p.onMouseUp=function(s,x,y)
        local wasDragging=s.dragging
        s.dragging=false;s:setCapture(false)
        if wasDragging then
            local distance=s.offset;local step=cardHeight(s)*.505+15
            if math.abs(distance)>24 then
                select(s,s.index+(distance<0 and 1 or -1)*math.max(1,math.floor(math.abs(distance)/step+.5)),true)
            elseif x then
                select(s,s.index+math.floor((x-s.width/2)/step+.5),true)
            else select(s,s.index) end
        end
        return true
    end
    p.onMouseUpOutside=p.onMouseUp
    p.onMouseWheel=function(s,delta)if delta~=0 then select(s,s.index+(delta>0 and 1 or -1),true)end;return true end
    self.content:addChild(p);table.insert(self.widgets,p);self.carouselPanel=p;select(p,p.index)
end

function W:canEditCurrent()
    local character=self.byCharacter and self.byCharacter[self.character]
    return self.browsed and self.browsed.id==self.currentId and self.selected and not self.waiting
        and not (character and character.unlockRequired) and AimohPMXPreview.isEnabled(self.player)
end

function W:toolMessage()
    return tr(self.waiting and "Loading" or "RoomActivateBeforeTools")
end

function W:requestMotionPage()
    if self:canEditCurrent() then
        self.toolNotice=nil
        self:setRoomPage("motions")
    else self.toolNotice=self:toolMessage() end
end

function W:requestPhysicsReset()
    if not self:canEditCurrent() then
        self.toolNotice=self:toolMessage()
    else
        local ok=self.actions.physicsReset(self.player)
        self.toolNotice=not ok and tr("Error") or nil
    end
end

function W:updateWear()
    local wearing=self.browsed and self.browsed.id==self.currentId
    local locked=self.browsed and self.browsed.outfitLocked and not wearing
    local skins=self.tab=="skins"
    self.skinButton.roomTitle=""
    local character=self.byCharacter[self.character]
    self.skinButton:setEnable(skins or (self.browsed~=nil and not (character and character.unlockRequired) and not self.waiting))
    self.wearButton.roomLook=skins and locked and not self.browsed.outfitPool and "outfitPurchase" or "primary"
    self.wearButton.roomTitle=tr(skins and (wearing and "Wearing" or locked and self.browsed.outfitPool and "RoomOutfitProcurement"
        or locked and "Buy" or "Wear")
        or (wearing and "CharacterEnabled" or "EnableCharacter"))
    local pendingUnlock=character and character.unlockRequired
    self.wearButton:setEnable(self.browsed~=nil and not wearing and (not locked or skins) and not pendingUnlock and not self.waiting)
    local available=self.browsed~=nil and not locked and not pendingUnlock
    for _,button in ipairs({self.pageButton,self.chestButton,self.physicsResetButton,
        self.gripButton,self.expressionButton,self.motionTab}) do button:setEnable(available) end
end
function W:previewEntry(entry)
    local id=entry and entry.id
    if id~=self.browseId then
        self.toolNotice=nil
        AimohPMXReadyRoom.release(self.player)
        self.previewAt=getTimestampMs()+85
    end
    self.browseId=id;self.browsed=entry
end
function W:applyBrowse()
    local character=self.byCharacter[self.character]
    local entry=self.browsed
    if not entry or not character or character.unlockRequired or self.waiting or self.exchange or self.drawAnimation then return end
    if not entry.outfitLocked then self:selectSkin(entry);return end
    if self.tab~="skins" or entry.id==self.currentId then return end
    if entry.outfitPool then self:setTab("outfitDraw");return end
    local status=economy.buyOutfit(self.player,entry.id)
    if status=="short" then self:openExchange("credits");return end
    if status=="unlocked" then
        entry.outfitLocked=false
        self:updateWear()
        self.toolNotice=tr("RoomOutfitAcquired")
    end
end
function W:refresh()
    self:closeWorkshopPrompt()
    economy.resetTestDraws(self.player,data.roomOutfitPools or {})
    self.currentId=AimohPMXPreview.modelId(self.player);self.currentLabel=tr("Choose");self.selected=nil
    self.characters={};self.byCharacter={}
    local equippedCharacter
    local defaultSkins=data.roomDefaultSkinNames or {}
    local optional=data.optionalModels or {}
    local optionalIds={};local available={}
    for _,id in ipairs(optional)do optionalIds[id]=true end
    local function add(id,index,meta)
        local character=self.byCharacter[meta.character]
        if not character then
            local card=data.roomRecruitment[meta.character]
            local locked=card~=nil and not data.roomDefaultUnlocked[card.pool]
                and not economy.characterUnlocked(self.player,card.pool)
            if self.drawAnimation and self.drawAnimation.character==meta.character then locked=true end
            character={name=meta.character,portrait=meta.portrait,skins={},locked=true,unlockRequired=not not locked}
            self.byCharacter[meta.character]=character;table.insert(self.characters,character)
        end
        if index==nil then return end
        local entry={id=id,index=index,meta=meta,outfitPool=(data.roomOutfitPools or {})[id]}
        character.locked=false;table.insert(character.skins,entry)
        local original=meta.isDefault==true or not not defaultSkins[meta.skin]
        entry.sortOrder=original and 0 or entry.outfitPool and 2 or 1
        if not entry.outfitPool and (not character.baseSkin or (original and not character.baseSkinIsOriginal)) then
            character.baseSkin=entry
            character.baseSkinIsOriginal=original
        end
        if id==self.currentId then equippedCharacter=meta.character;self.currentLabel=meta.skin;self.selected=entry end
    end
    for index=0,AimohPMXPreview.modelCount()-1 do
        local id=AimohPMXPreview.modelIdAt(index);local meta=data.models[id]
        if not meta and not data.excluded[id] then
            local name,skin=AimohPMXPreview.modelLabel(index):match("^(.-) / (.+)$")
            meta={character=name or AimohPMXPreview.modelLabel(index),skin=data.skinNames[skin] or skin or tr("Default")}
        end
        if AimohPMXPreview.modelListed(index) and meta then
            if optionalIds[id] then available[id]=index else add(id,index,meta) end
        end
    end
    for _,id in ipairs(optional)do add(id,available[id],data.models[id])end
    for _,character in ipairs(self.characters)do
        table.sort(character.skins,function(a,b)
            if a.sortOrder~=b.sortOrder then return a.sortOrder<b.sortOrder end
            return a.index<b.index
        end)
        local base=character.baseSkin
        if base then character.portrait=base.meta.portrait or character.portrait end
        for _,skin in ipairs(character.skins)do
            skin.outfitLocked=skin~=base and not economy.outfitOwned(self.player,skin.id)
            if self.drawAnimation and self.drawAnimation.outfit==skin.id then skin.outfitLocked=true end
        end
    end
    if not self.byCharacter[self.character] then self.character=equippedCharacter or (self.characters[1] and self.characters[1].name) end
    local character=self.byCharacter[self.character]
    local entry=character and (character.baseSkin or character.skins[1])
    if character and not character.unlockRequired then
        for _,skin in ipairs(character.skins) do
            if skin.id==(self.browseId or self.currentId) then entry=skin;break end
        end
    end
    self:previewEntry(entry)
    self:rebuildRoster()
    self:refreshControls()
end
function W:setTab(tab)
    if self.tab~=tab then self:closeWorkshopPrompt() end
    if tab=="overview" and self.tab=="skins" and self.browsed and self.browsed.outfitLocked
        and self.browsed.id~=self.currentId then
        local character=self.byCharacter[self.character]
        if character then self:previewEntry(character.baseSkin or character.skins[1]) end
    end
    if self.tab~=tab then
        self.expressionSaving=false;self.roomPageFade=getTimestampMs()
        self.drawNotice=nil
        if tab=="expression" then
            self.cameraBeforeExpression={self.zoom,self.cameraPan,self.cameraFocus}
            self.cameraTransition={getTimestampMs(),self.zoom,self.cameraPan,self.cameraFocus,3.25,0,1}
        elseif self.tab=="expression" and self.cameraBeforeExpression then
            local old=self.cameraBeforeExpression
            self.cameraTransition={getTimestampMs(),self.zoom,self.cameraPan,self.cameraFocus,old[1],old[2],old[3]}
            self.cameraBeforeExpression=nil
        end
    end
    self.toolNotice=nil
    self.tab=tab;self:refreshControls()
end
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
                    local selectedOption=AimohPMXPreview.selectedOption(self.player,group)
                    local pictures=self.selected.meta.partImages
                    local groupPictures=pictures and pictures[AimohPMXPreview.groupLabel(self.player,group)]
                    for option=0,count-1 do
                        local w=(width-16)/3;local x=(option%3)*(w+8);local yy=y+math.floor(option/3)*85
                        local optionLabel=AimohPMXPreview.optionLabel(self.player,group,option)
                        self:addTile(x,yy,w,77,optionLabel,groupPictures and groupPictures[optionLabel],selectedOption==option,function(s)
                            if s.actions.choice(s.player,group,option) then s:refreshControls(true) end
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
function W:refreshControls(keepScroll)
    if self.folded or self.foldStart then return end
    if self.roomPage=="motions" then self:refreshMotionControls();return end
    local editable=self:canEditCurrent()
    self.preview:setVisible(true)
    local scroll=keepScroll and self.content:getYScroll() or 0
    self:clearContent()
    local enabled=AimohPMXPreview.isEnabled(self.player)
    self.restoreButton:setEnable(enabled)
    self.content:setX(self.tab=="skins" and self.overviewX-57 or self.rightX)
    self.content:setWidth(self.tab=="skins" and self.overviewWidth+64 or self.rightWidth)
    self.content:setY(self.tab=="skins" and math.floor(self.height*.19) or 91)
    self.content:setHeight(self.tab=="skins" and math.floor(self.height*.57) or self.height-self.content.y-112)
    local y=0;local width=self.rightWidth-18;local showExpressionSlots=false
    if self.tab=="overview" then
        y=0
    elseif self.tab=="outfitDraw" then
        y=0
    elseif self.tab=="skins" then
        local character=self.byCharacter[self.character]
        if character and not character.locked then self:carousel(character) end
        y=self.content.height
    elseif not editable then
        self:addLabel(self:toolMessage(),8);y=44
    elseif self.tab=="chest" and not AimohPMXPreview.hasChestPhysics(self.player) then
        self:addLabel(tr("RoomChestUnavailable"),8);y=44
    else
        if self.tab=="expression" then
            local id=self.currentId
            local rows=AimohPMXReadyRoom.expressionRows(self.player,id)
            y=self:drawExpressionPresets(rows,y,width)
            showExpressionSlots=#rows>0
            if showExpressionSlots then
                self.content:setHeight(self.expressionFooter.y-self.content.y-6)
                if AimohPMXReadyRoom.motionState(self.player).name~="" then
                    self:addLabel(tr("ExpressionVmd"),y);y=y+28
                end
                local group
                for _,row in ipairs(rows)do
                    if group~=row.group then group=row.group;self:addLabel(tr(group),y);y=y+31 end
                    local field=row.index
                    self:addLabel(row.label,y,18)
                    local slider=ISSliderPanel:new(5,y+24,width-10,20,self,function(w,value)
                        local error=AimohPMXReadyRoom.expressionSet(w.player,id,field,value/100)
                        if error~="" then print("[AimohPMXPreview] "..error);w.status=tr("Error") end
                    end)
                    slider:initialise();slider:instantiate();slider:setDoButtons(false);slider:setValues(0,100,1,10,true)
                    slider:setCurrentValue(row.value*100,true)
                    local render=slider.render
                    slider.render=function(s)
                        render(s);s:drawTextRight(string.format("%d%%",math.floor(s.currentValue+.5)),s.width,-21,.81,.88,.92,1,UIFont.Small)
                    end
                    slider.roomSlider=true;self.content:addChild(slider);table.insert(self.widgets,slider);y=y+49
                end
            else self:addLabel(tr("ExpressionEmpty"),y);y=y+30 end
        elseif self.tab=="grip" then
            local family=AimohPMXReadyRoom.gripFamily(self.player)
            self.gripFamily=family
            if family=="" then
                self:addLabel(tr("GripEquip"),y);y=y+32
            else
                self:addLabel(tr(family=="handgun" and "GripHandgun" or "GripLongGun"),y);y=y+32
                local values=AimohPMXReadyRoom.gripValues(self.player,family)
                local groups={
                    {"GripSupport",0,12,3,""},{"GripSupportWrist",6,90,5,"°"},
                    {"GripPrimary",3,12,3,""},{"GripPrimaryWrist",9,90,5,"°"},
                }
                for _,group in ipairs(groups)do
                    local label,start,limit,page,unit=unpack(group)
                    self:addLabel(tr(label),y);y=y+28
                    for i,axis in ipairs({"X","Y","Z"})do
                        local field=start+i-1
                        self:addLabel(axis,y,17)
                        local slider=ISSliderPanel:new(34,y+2,width-46,20,self,function(w,value)
                            if not AimohPMXReadyRoom.gripSet(w.player,family,field,value)then w.status=tr("Error") end
                        end)
                        slider:initialise();slider:instantiate();slider:setDoButtons(false);slider:setValues(-limit,limit,1,page,true)
                        slider:setCurrentValue(values[field+1] or 0,true)
                        local render=slider.render
                        slider.render=function(s)
                            render(s)
                            s:drawTextRight(string.format("%+d",s.currentValue)..unit,s.width,20,.81,.88,.92,1,UIFont.Small)
                        end
                        slider.roomSlider=true;self.content:addChild(slider);table.insert(self.widgets,slider);y=y+39
                    end
                end
                local b=button(self.content,0,y,width,tr("Reset"),self,function(w)
                    if AimohPMXReadyRoom.gripSet(w.player,family,-1,0)then w:refreshControls(true) end
                end)
                table.insert(self.widgets,b);y=y+38
            end
        elseif self.tab=="chest" then
            self:addLabel("Jolt",y);y=y+32
            local sliders={
                {"ChestJoltFollow",8,0,100},
                {"ChestJoltLateral",9,0,250},
                {"ChestJoltLanding",10,0,200},
                {"ChestJoltStrain",11,0,200},
                {"ChestSoftness",0,0,100},{"ChestDamping",1,0,100},
                {"ChestInertia",2,0,100},{"ChestStrength",3,0,100},
                {"ChestXpbdVertical",5,60,140},{"ChestXpbdLateral",4,60,140},
                {"ChestXpbdDepth",6,60,140},{"ChestXpbdCushion",7,0,60},
            }
            for _,row in ipairs(sliders)do
                local key,field,minimum,maximum=row[1],row[2],row[3],row[4]
                self:addLabel(tr(key),y,24);y=y+32
                local slider=ISSliderPanel:new(4,y,width-12,24,self,function(w,value,s)
                    if not w.actions.chestPhysics(w.player,field,value)then w.status=tr("Error")end
                end)
                slider:initialise();slider:instantiate();slider:setValues(minimum,maximum,1,10,true)
                slider:setCurrentValue(AimohPMXPreview.chestPhysicsSlider(self.player,field),true)
                local base=slider.render
                slider.render=function(s)base(s);s:drawTextRight(tostring(math.floor(s.currentValue+.5))..(field>=4 and "%" or ""),s.width,-28,1,1,1,1,UIFont.Medium)end
                slider.roomSlider=true;self.content:addChild(slider);table.insert(self.widgets,slider);y=y+46
            end
            local b=button(self.content,0,y,width,tr("Reset"),self,function(w)if w.actions.chestDefaults(w.player)then w:refreshControls()end end)
            table.insert(self.widgets,b);y=y+36
            if AimohPMXPreview.chestPhysicsStatus(self.player)~="" then self:addLabel(tr("ChestSuspended"),y);y=y+32 end
        elseif self.tab=="parts" then
            y=self:drawGroups(self:partRows(false),y,width)
            y=self:drawGroups(self:partRows(true),y,width)
            for row=0,AimohPMXPreview.materialCount(self.player)-1 do
                local index=AimohPMXPreview.materialIndex(self.player,row);local available=AimohPMXPreview.materialAvailable(self.player,index)
                local b=button(self.content,0,y,width,AimohPMXPreview.materialLabel(self.player,index),self,function(s)
                    if s.actions.material(s.player,index,not AimohPMXPreview.materialVisible(s.player,index))then s:refreshControls(true)end
                end)
                b:setEnable(available);b.roomActive=AimohPMXPreview.materialVisible(self.player,index);table.insert(self.widgets,b);y=y+34
            end
            if AimohPMXPreview.presetCount(self.player)>0 then
                self:addLabel(tr("Presets"),y);y=y+30
                for index=0,AimohPMXPreview.presetCount(self.player)-1 do
                    local b=button(self.content,0,y,width,AimohPMXPreview.presetLabel(self.player,index),self,function(s)if s.actions.preset(s.player,index)then s:refreshControls(true)end end)
                    table.insert(self.widgets,b);y=y+34
                end
            end
            local half=math.floor((width-8)/2)
            local reset=button(self.content,0,y,half,tr("Reset"),self,self.onReset)
            local reload=button(self.content,half+8,y,half,tr("Reload"),self,self.onReload)
            table.insert(self.widgets,reset);table.insert(self.widgets,reload);y=y+38
        end
    end
    self:refreshExpressionSlots(showExpressionSlots)
    self:updateWear()
    self.content:setScrollHeight(math.max(y,self.content.height))
    self.content:setYScroll(clamp(scroll,self.content.height-self.content:getScrollHeight(),0))
    self.status=self.waiting and tr("Loading") or enabled and tr("Saved") or tr("Original")
    self:layoutOverview()
    self.content:setVisible(self.tab~="overview" and self.tab~="outfitDraw")
end
function W:selectSkin(entry)
    if self.waiting or entry.outfitLocked or self.currentId==entry.id then return false end
    if self.actions.enable(self.player,entry.index)then
        self.toolNotice=nil;self.waiting=true;self.yaw=0;self:refresh()
        return true
    end
    self.status=tr("Error");return false
end

function W:onCharacter(character)
    if self.waiting or self.drawAnimation or not character then return end
    if self.character~=character.name then
        self:closeWorkshopPrompt()
        roomSound("Select")
        self.character=character.name
        self.drawNotice=nil
        self.yaw=0;self.zoom=1.03;self.cameraPan=0;self.cameraFocus=0
        self.cameraTransition=nil;self.cameraBeforeExpression=nil
        if self.preview.dragging then self.preview:onMouseUp() end
        local entry=character.baseSkin or character.skins[1]
        if not character.unlockRequired then
            for _,skin in ipairs(character.skins) do if skin.id==self.currentId then entry=skin;break end end
        end
        self:previewEntry(entry)
        self:setTab("overview")
    end
end
function W:drawExpressionPresets(rows,y,width)
    local labels={}
    for _,row in ipairs(rows)do labels[row.label]=row.index end
    local eyes=self.selected.meta.groups and self.selected.meta.groups.eyes
    local group
    if eyes then
        for i=0,AimohPMXPreview.groupCount(self.player)-1 do
            if AimohPMXPreview.groupLabel(self.player,i)==eyes.label then group=i;break end
        end
    end
    local blinkLabels=data.roomBlinkLabels or {}
    local blink=labels[blinkLabels[1]] or labels[blinkLabels[2]]
    if group~=nil or blink~=nil then
        local half=math.floor((width-8)/2)
        for i,key in ipairs({"EyeOpen","EyeClosed"})do
            local option=i-1
            local b=button(self.content,option*(half+8),y,half,tr(key),self,function(w)
                if not w:canEditCurrent() then return end
                if group~=nil then
                    if not w.actions.choice(w.player,group,option)then return end
                else
                    if AimohPMXReadyRoom.expressionSet(w.player,w.currentId,blink,option)~="" then return end
                    w.actions.expressionRemember(w.player)
                end
                w:refreshControls(true)
            end)
            b.roomActive=group~=nil and AimohPMXPreview.selectedOption(self.player,group)==option
            table.insert(self.widgets,b)
        end
        y=y+36
    end
    local presets=data.roomFacialPresets or {}
    if #rows==0 or #presets==0 then return y end
    self:addLabel(tr("RoomFacePresets"),y,19);y=y+29
    local cell=math.floor((width-(#presets-1)*6)/#presets)
    for i,preset in ipairs(presets)do
        local payload=expressionPreset(preset,labels)
        if payload then
            local b=button(self.content,(i-1)*(cell+6),y,cell,tr(preset[1]),self,function(w)
                if not w:canEditCurrent() then return end
                local error=AimohPMXReadyRoom.expressionLoad(w.player,w.currentId,payload)
                if error~="" then print("[AimohPMXPreview] "..error);return end
                w.actions.expressionRemember(w.player)
                w:refreshControls(true)
            end)
            table.insert(self.widgets,b)
        end
    end
    return y+38
end

function W:onRestore()self.actions.disable(self.player);if AimohPMXReadyRoom then AimohPMXReadyRoom.release(self.player)end;self.waiting=false;self.selected=nil;self:refresh()end
function W:onReset()if self.actions.reset(self.player)then self:refreshControls()end end
function W:onReload()if self.actions.reload(self.player)then self:refreshControls()end end
function W:update()
    ISCollapsableWindow.update(self)
    self:updateDrawAnimation()
    if self.cameraTransition then
        local t=self.cameraTransition
        local phase=ease((getTimestampMs()-t[1])/235)
        self.zoom=t[2]+(t[5]-t[2])*phase
        self.cameraPan=t[3]+(t[6]-t[3])*phase
        self.cameraFocus=t[4]+(t[7]-t[4])*phase
        if phase==1 then self.cameraTransition=nil end
    end
    if self.railStart then
        local phase=ease((getTimestampMs()-self.railStart)/160)
        local width=self.rosterExpanded and self.gridWidth or self.railWidth
        self.sidebar:setWidth(math.floor(self.railFrom+(width-self.railFrom)*phase+.5))
        self.rosterToggle:setX(self.sidebar.x+self.sidebar.width+8)
        if phase==1 then self.railStart=nil end
    end
    if self.foldStart then
        local t=clamp((getTimestampMs()-self.foldStart)/160,0,1)
        self:setHeight(math.floor(self.foldFrom+(self.foldTo-self.foldFrom)*ease(t)+.5))
        if t==1 then
            self.foldStart=nil;self:roomVisibility()
            if not self.folded then self.pageFade=getTimestampMs();self:refreshControls() end
        end
    end
    if self.waiting then
        if AimohPMXPreview.hasModelFailure and AimohPMXPreview.hasModelFailure(self.player)then self.waiting=false;self:refresh();self.status=tr("Error")
        elseif AimohPMXPreview.isReady(self.player)then self.waiting=false;self:refresh()end
    elseif AimohPMXPreview.modelId(self.player)~=self.currentId then
        self:refresh()
    end
    if not self.folded and not self.foldStart and self.roomPage=="motions" then self:refreshMotionControls() end
    if not self.folded and not self.foldStart and self.roomPage=="appearance" and self.tab=="grip"
        and self:canEditCurrent() and AimohPMXReadyRoom.gripFamily(self.player)~=(self.gripFamily or "") then
        self:refreshControls(true)
    end
end
function W:prerender()
    local header=self:titleBarHeight()
    if self.folded or self.foldStart then
        surface(self,0,0,self.width,math.min(header,self.height),.68)
        return
    end
    local background=image(data.background)
    if background then
        local w,h=self.width,self.height
        self:setStencilRect(0,0,w,h)
        local scale=math.max(w/background:getWidth(),h/background:getHeight())
        local bw,bh=background:getWidth()*scale,background:getHeight()*scale
        self:drawTextureScaled(background,(w-bw)/2,(h-bh)/2,bw,bh,1,1,1,1)
        self:clearStencilRect()
    end
    surface(self,0,0,self.width,header,.67)
    local titles={skins="RoomOutfits",outfitDraw="RoomOutfitProcurement",parts="RoomPartSwitch",chest="ChestPhysics",grip="GripCalibration",expression="Expression"}
    local page=self.roomPage=="motions" and "RoomMotion" or titles[self.tab]
    if page then label(self,tr(page),76,14,23,self.width-180) end
    if self.roomPage=="motions" then return end
    local character=self.byCharacter[self.character]
    if (self.tab=="skins" or self.tab=="outfitDraw") and self.browsed then
        local x=math.floor(self.width*.045)
        local inPool=self.tab=="outfitDraw"
        local width=math.floor(self.width*.245)
        local meta=self.browsed.meta
        local pool=self.browsed.outfitPool
        local name=pool and pool.name or meta.officialSkin or meta.skin
        local descriptions=data.skinDescriptions or {}
        local text=descriptions[self.browsed.id] or descriptions[meta.card or ""]
        local y=math.min(math.floor(self.height*(inPool and .605 or .668)),self.height-42-80-#(text or {})*18)
        if inPool and pool then
            local cover=image(pool.banner)
            if cover then
                local height=math.floor(width*cover:getHeight()/cover:getWidth())
                self:drawTextureScaled(cover,x,y-height-14,width,height,1,1,1,1)
            end
        end
        label(self,name,x,y,32,width,.94,.94,.94)
        label(self,self.character,x,y+40,19,width,.78,.78,.77)
        self:drawRect(x,y+64,72,4,1,.875,.620,0)
        description(self,text,x,y+80,width)
        if inPool then
            local misses=self.drawAnimation and self.drawAnimation.misses or economy.outfitMisses(self.player,self.browsed.id)
            self:drawPity("RoomOutfitPity",economy.outfitGuarantee-misses)
        elseif self.toolNotice then
            label(self,self.toolNotice,self.wearButton.x,self.wearButton.y-31,16,self.wearButton.width)
        end
    elseif self.tab=="overview" and character and not character.locked and character.unlockRequired then
        self:drawLockedCharacterInfo(character)
    elseif self.tab=="overview" and character then
        local x,y,width=self.overviewX,self.overviewY,self.overviewWidth
        frosted(self,self,x,y,width,258,.72)
        label(self,self.character,x+11,y+14,33,width-22,.95,.95,.94)
        self:drawRect(x+11,y+58,25,4,1,.875,.620,0)
        local biography=(data.roomCharacterDescriptions or {})[character.name]
        if self.toolNotice then
            surface(self,x,y+265,width,37,.92)
            label(self,self.toolNotice,x+10,y+274,16,width-20)
        elseif biography then
            frosted(self,self,x,y+265,width,18+#biography*18,.72)
            description(self,biography,x+10,y+274,width-20)
        end
    end
end
function W:drawLockedCharacterInfo(character)
    local card=data.roomRecruitment and data.roomRecruitment[character.name]
    if not card then return end
    local x=math.max(math.floor(self.width*.122),self.sidebar.x+self.sidebar.width+24)
    local biography=(data.roomCharacterDescriptions or {})[character.name]
    local width=math.floor(self.width*.164)
    local bannerHeight=math.floor(width/2)
    local blockHeight=bannerHeight+105+#(biography or {})*18
    local y=math.min(math.floor(self.height*.65),self.height-42-blockHeight)
    local banner=image(card.banner)
    if banner then
        frosted(self,self,x,y,width,bannerHeight,.80)
        self:drawTextureScaled(banner,x,y,width,bannerHeight,1,1,1,1)
        self:drawRectBorder(x,y,width,bannerHeight,.52,.63,.70,.70)
    end
    local tag=card.element
    surface(self,x,y+bannerHeight+8,width,28,.70)
    label(self,tag,x+8,y+bannerHeight+14,17,width-16,card.color[1],card.color[2],card.color[3])
    label(self,character.name,x,y+bannerHeight+43,30,width,.96,.96,.96)
    if card.tags and card.tags~="" then
        label(self,card.tags,x,y+bannerHeight+81,16,width,.89,.92,.92)
    end
    description(self,biography,x,y+bannerHeight+105,width)
    local misses=self.drawAnimation and self.drawAnimation.misses or economy.characterMisses(self.player)
    self:drawPity("RoomSharedPity",economy.characterGuarantee-misses)
end

function W:drawPity(title,remaining)
    local button=self.drawButtons[2]
    local right=button.x+button.width
    local top=button.y-56
    number(self,remaining,right,top,16,.94,.95,.95,true)
    label(self,tr(title),right-208,top,16,164,.89,.91,.91)
    if self.drawNotice then label(self,self.drawNotice,self.drawButtons[1].x,top,16,220,.96,.87,.73) end
end

function W:render()
    if self.folded or self.foldStart then return end
    if self.roomPageFade then
        local phase=ease((getTimestampMs()-self.roomPageFade)/170)
        self:drawRect(self.overviewX-12,self.overviewY,self.overviewWidth+24,math.floor(self.height*.59),.19*(1-phase),.05,.07,.07)
        if phase==1 then self.roomPageFade=nil end
    end
    if self.pageFade then
        local phase=ease((getTimestampMs()-self.pageFade)/150)
        self:drawRect(0,0,self.width,self.height,.24*(1-phase),.05,.07,.07)
        if phase==1 then self.pageFade=nil end
    end
    if self.drawAnimation then self:drawRecruitmentResults() end
end
function W:close()
    if self.closed then return end
    self.closed=true
    self:closeWorkshopPrompt()
    self:closeExchange()
    if W.instances[self.playerIndex]==self then W.instances[self.playerIndex]=nil end
    self:setVisible(false)
    self:removeFromUIManager()
    self:refreshExpressionSlots(false)
    if self.motionSeek then self.motionSeek:cancel() end
    if self.motionSpeed then self.motionSpeed.dragInside=false end
    self:closeMotionPathDialog()
    if self.carouselPanel then self.carouselPanel.dragging=false;self.carouselPanel:setCapture(false)end
    self:clearContent();self.preview.dragging=false;self.preview:setCapture(false);self.sidebar.roomDown=false;self.sidebar:setCapture(false)
    if AimohPMXReadyRoom then AimohPMXReadyRoom.release(self.player)end
end
function W.open(player,actions)
    if not AimohPMXReadyRoom then player:Say(getText("ContextMenu_AimohPMX_JavaUnavailable"));return end
    local index=player:getPlayerNum()
    local window=W.instances[index]
    if window and window.player==player then
        window:bringToTop()
        return window
    end
    if window then window:close() end
    data=loadData()
    local sw,sh=getCore():getScreenWidth(),getCore():getScreenHeight()
    local width=math.min(1510,sw-48);local height=math.min(880,sh-62)
    window=ISCollapsableWindow.new(W,math.floor((sw-width)/2),math.floor((sh-height)/2),width,height)
    window.player=player;window.playerIndex=index;window.actions=actions
    window.resizable=false;window.drawFrame=false;window.yaw=0;window.zoom=1.03
    window.cameraPan=0;window.cameraFocus=0
    window:initialise();window:addToUIManager();W.instances[index]=window
    window:setVisible(true);window:bringToTop();return window
end
function W.closeAll()
    local windows=W.instances
    W.instances={}
    for _,window in pairs(windows)do window:close()end
    data=nil;textures={}
end
function W.closeFor(player)
    if not player then return end
    local window=W.instances[player:getPlayerNum()]
    if window and window.player==player then window:close() end
end
return W
