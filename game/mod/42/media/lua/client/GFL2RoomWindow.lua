require "ISUI/ISCollapsableWindow"
require "ISUI/ISScrollingListBox"
require "ISUI/ISComboBox"
require "ISUI/ISButton"
require "ISUI/ISTextBox"
require "Vehicles/ISUI/ISUI3DScene"
require "RadioCom/ISUIRadio/ISSliderPanel"
local loadData=require "GFL2RoomData"
local economy=require "GFL2RoomEconomy"
local signatureWeapons=require "GFL2SignatureWeapon"
local weaponSkins=require "GFL2WeaponSkins"
local multiplayer=require "GFL2MultiplayerClient"
local data
local textures={}
local function image(path)
    if not path then return nil end
    if not textures[path] then textures[path]=getTexture(path) end
    return textures[path]
end
local function portraitImage(path)
    if not path then return nil end
    return image(path:gsub("/round%-","/portrait-"))
end
local art="media/ui/GFL2ReadyRoom/room-"
local function roomSound(action)
    getSoundManager():playUISound("GFL2Room"..action)
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
local function hoverAmount(item,over)
    local target=over and 1 or 0
    local previous=item.roomHoverTo or 0
    if previous==target and not item.roomHoverStart then return target end
    local now=getTimestampMs()
    local value=previous
    if item.roomHoverStart then
        local elapsed=(now-item.roomHoverStart)/(previous==1 and 115 or 150)
        value=item.roomHoverFrom+(previous-item.roomHoverFrom)*ease(elapsed)
        if elapsed>=1 then item.roomHoverStart=nil end
    end
    if previous~=target then
        item.roomHoverFrom=value;item.roomHoverTo=target;item.roomHoverStart=now
    end
    return value
end
local function surface(p,x,y,w,h,a)
    if h<=0 or w<=0 then return end
    p:drawRect(x,y,w,h,a,.073,.108,.124)
    local glass=image(art.."glass.png")
    if glass then p:drawTextureScaled(glass,x,y,w,h,a*.28,1,1,1) end
    p:drawRect(x,y,w,1,a*.25,.66,.74,.75)
    p:drawRect(x,y+h-1,w,1,a*.58,.025,.040,.045)
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
local roomText,roleNames,outfitNames={},{},{}
local biographies,outfitDescriptions,recruitmentText,descriptionCache={},{},{},{}
local descriptionLineHeight
local function tr(key) return roomText[key] or data.text[key] or key end
local function loadRoomText()
    roomText={}
    for key,value in pairs(data.text) do
        roomText[key]=(getTextOrNull and getTextOrNull("UI_GFL2_"..key)) or value
    end
    roleNames={};outfitNames={};biographies={};outfitDescriptions={};recruitmentText={};descriptionCache={}
    if getTextOrNull then
        local language=Translator and Translator.getLanguage():name()
        local localizedProfiles=language~="CN" and language~="CH"
        for id,model in pairs(data.models) do
            outfitNames[id]=getTextOrNull("UI_GFL2_Outfit_"..id)
            if localizedProfiles then outfitDescriptions[id]=getTextOrNull("UI_GFL2_OutfitDescription_"..id) end
            if model.pack and model.character and not recruitmentText[model.character] then
                roleNames[model.character]=getTextOrNull("UI_GFL2_Role_"..model.pack)
                recruitmentText[model.character]={}
                if localizedProfiles then
                    biographies[model.character]=getTextOrNull("UI_GFL2_Biography_"..model.pack)
                    recruitmentText[model.character]={
                        element=getTextOrNull("UI_GFL2_RecruitPhase_"..model.pack),
                        tags=getTextOrNull("UI_GFL2_RecruitTags_"..model.pack)
                    }
                end
            end
        end
    end
end
local function outfitName(entry)
    if outfitNames[entry.id] then return outfitNames[entry.id] end
    if entry.isDefault then return tr("Default") end
    return entry.outfitPool and entry.outfitPool.name or entry.meta.officialSkin or entry.meta.skin
end
local function isLocalMotion(file)
    return file~=nil and file:find("[/\\]")~=nil
end
local function motionDisplayName(file)
    if file:sub(1,7)=="sample:" then return file:sub(8) end
    return file:match("([^/\\]+)$") or file
end
local function fitText(value,width,font,suffix)
    local tm=getTextManager()
    if not width or tm:MeasureStringX(font,value)<=width then return value end
    local ellipsis=suffix or "..."
    if tm:MeasureStringX(font,ellipsis)>width then return "" end
    local ends={}
    for index=2,#value do
        local code=value:byte(index)
        if code<56320 or code>57343 then ends[#ends+1]=index-1 end
    end
    ends[#ends+1]=#value
    local lo,hi=0,#ends
    while lo<hi do
        local mid=math.floor((lo+hi+1)/2)
        if tm:MeasureStringX(font,value:sub(1,ends[mid])..ellipsis)<=width then lo=mid
        else hi=mid-1 end
    end
    return value:sub(1,ends[lo] or 0)..ellipsis
end
local function slotFontSize(tm,font,height)
    local lineHeight=tm.getFontHeight and tm:getFontHeight(font) or height
    return math.min(1,height/math.max(1,lineHeight)),lineHeight
end
local function textInSlot(panel,value,x,y,height,width,r,g,b,a,align,font)
    font=font or UIFont.Small
    local tm=getTextManager()
    local zoom,lineHeight=slotFontSize(tm,font,height)
    local text=fitText(value,width and width/zoom,font)
    local top=y+(height-lineHeight*zoom)/2
    if zoom<1 then
        local left=align and x-tm:MeasureStringX(font,text)*zoom*align or x
        panel:drawTextZoomed(text,left,top,zoom,r,g,b,a,font)
    elseif align==.5 then panel:drawTextCentre(text,x,top,r,g,b,a,font)
    elseif align==1 then panel:drawTextRight(text,x,top,r,g,b,a,font)
    else panel:drawText(text,x,top,r,g,b,a,font) end
end
local function label(panel,value,x,y,height,width,r,g,b,a,centered)
    a=(a or 1)*(panel.roomEnterAlpha or 1)
    value=roleNames[value] or value
    local row=(data.roomLabels and data.roomLabels[value]) or data.labels[value]
    local t=row and image(row.path)
    if t then
        local scale=math.min(height/row.height,(width or row.width)/row.width)
        local w,h=row.width*scale,row.height*scale
        panel:drawTextureScaled(t,centered and x-w/2 or x,y+(height-h)/2,w,h,a or 1,r or .92,g or .92,b or .91)
    else
        textInSlot(panel,value,x,y,height,width,r or .92,g or .92,b or .91,a or 1,centered and .5)
    end
end
local function descriptionHeight()
    local tm=getTextManager()
    return math.max(18,(tm.getFontHeight and tm:getFontHeight(UIFont.Small) or 16)+2)
end
local function describe(source,width)
    if not source then return nil end
    local lineHeight=descriptionHeight()
    if lineHeight~=descriptionLineHeight then
        descriptionCache={}
        descriptionLineHeight=lineHeight
    end
    local byWidth=descriptionCache[source]
    if not byWidth then byWidth={};descriptionCache[source]=byWidth end
    if byWidth[width] then return byWidth[width] end
    local results={plain=type(source)=="string"}
    local lines=source
    if results.plain then
        lines={}
        for row in (source.."\n"):gmatch("(.-)\n") do lines[#lines+1]=row end
    end
    for _,row in ipairs(lines) do
        local baked=not results.plain and ((data.roomLabels and data.roomLabels[row]) or data.labels[row])
        if baked then results[#results+1]=row
        else
            local rest=row
            repeat
                local line=fitText(rest,width,UIFont.Small,"")
                if line~="" and #line<#rest then
                    local boundary=line:match(".*()%s+%S*$")
                    if boundary and boundary>#line/2 then line=line:sub(1,boundary-1) end
                end
                if line=="" then line=rest end
                results[#results+1]=line
                rest=rest:sub(#line+1):gsub("^%s+","")
            until rest==""
        end
    end
    byWidth[width]=results
    return results
end
local function description(panel,lines,x,y,width)
    local step=descriptionHeight()
    for i,line in ipairs(lines or {}) do
        local top=y+(i-1)*step
        if lines.plain then panel:drawText(line,x,top,.84,.84,.82,1,UIFont.Small)
        else label(panel,line,x,top,15,width,.84,.84,.82) end
    end
end
local function featureIcon(p,kind,x,y,size,a)
    local glyph=image(art..kind..".png")
    if glyph then p:drawTextureScaled(glyph,x,y,size,size,a,.83,.89,.89) end
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

local function tile(panel,x,y,w,h,path,title,captionHeight,active,hover,pressed)
    surface(panel,x,y,w,h,.8)
    local bodyHeight=h-captionHeight
    local t=image(path)
    if t then panel:drawTextureScaledAspect(t,x+2,y+2,w-4,bodyHeight,1,1,1,1)
    else
        panel:drawRect(x+w*.3,y+12,w*.4,math.max(8,bodyHeight-23),.16,.40,.60,.63)
        panel:drawRectBorder(x+w*.3,y+12,w*.4,math.max(8,bodyHeight-23),.45,.4,.6,.63)
    end
    panel:drawRect(x,y+bodyHeight,w,captionHeight,.82,.035,.05,.055)
    label(panel,title,x+6,y+bodyHeight+4,captionHeight-8,w-12)
    if hover>0 or pressed then
        panel:drawRect(x+1,y+1,w-2,h-2,pressed and .22 or hover*.08,.64,.81,.82)
    end
    if active or hover>0 then
        panel:drawRectBorder(x,y,w,h,active and .90 or hover*.54,active and 1 or .56,active and .71 or .72,active and .38 or .75)
    end
    if active then
        panel:drawRect(x,y+h-2,w,2,1,1,.71,.38)
    end
end
GFL2RoomWindow=ISCollapsableWindow:derive("GFL2RoomWindow")
GFL2RoomWindow.instances={}
local W=GFL2RoomWindow
local motionEntryPlayers={}
local function motionEntryVisible(window)
    return motionEntryPlayers[window.playerIndex]==window.player
end
function W:titleBarHeight() return 50 end
local function rosterCard(window, column, y)
    local cell = math.floor((window.gridWidth-12)/3)
    local size = cell-8
    return 4+(column-1)*cell,y+3,size,size+20
end

function W:rosterAt(x,y)
    local rail = self.sidebar
    local row = rail:rowAt(x,y)
    local item = rail.items[row]
    if not item or item.item.header then return nil end
    local rowY = rail:topOfItem(row)
    if self.rosterExpanded then
        local column = math.floor((x-4)/math.floor((self.gridWidth-12)/3))+1
        local character = item.item[column]
        if not character then return nil end
        local left,top,width,height = rosterCard(self,column,rowY)
        if x>=left and x<left+width and y>=top and y<top+height then return character,row end
    elseif (x-55)^2+(y-rowY-item.height/2)^2<=46^2 then
        return item.item,row
    end
end

local function hideTooltip(button)
    local tooltip=button.tooltipUI
    if tooltip and tooltip:getIsVisible() then
        tooltip:setVisible(false)
        tooltip:removeFromUIManager()
    end
end

local function button(parent,x,y,w,title,target,callback)
    local b=ISButton:new(x,y,w,28,"",target,callback);b:initialise();b.roomTitle=title;b:setSound("activate","GFL2RoomSelect")
    b.setVisible=function(s,visible)
        if not visible then hideTooltip(s) end
        ISButton.setVisible(s,visible)
    end
    b.backgroundColor={r=.149,g=.204,b=.227,a=.84}
    b.borderColor={r=.45,g=.56,b=.60,a=.38}
    b.roomHover=0
    b.prerender=function(s)
        local enabled=s.enable~=false
        s.roomHover=hoverAmount(s,enabled and (s.mouseOver or s.pressed or s.joypadFocused))
        local c=s.backgroundColor
        local a=enabled and c.a or .51
        if s.roomTab then a=.35+s.roomHover*.36 end
        if s.roomLook=="bare" then a=s.roomHover*.26 end
        if s.roomLook=="quiet" then a=.18+s.roomHover*.30 end
        if s.roomLook=="navigation" then c={r=.12,g=.17,b=.19};a=.85 end
        local primary=s.roomLook=="primary" or s.roomLook=="outfitPurchase" or s.roomLook=="drawTen"
        if primary and enabled then c={r=.15,g=.32,b=.31};a=.98 end
        if s.roomLook=="drawOnce" then a=enabled and .88 or .53 end
        if s.roomTile then a=.40+s.roomHover*.50 end
        local glow=s.roomHover*(s.pressed and -.055 or .075)
        a=a*(s.roomEnterAlpha or 1)
        local r,g,b=c.r+glow,c.g+glow,c.b+glow
        s:drawRect(0,0,s.width,s.height,a,r,g,b)
        if s.roomLook~="bare" and s.roomLook~="quiet" then
            s:drawRect(0,0,s.width,1,a*(s.pressed and .06 or .23),.72,.83,.83)
            s:drawRect(0,s.height-1,s.width,1,a*.57,.03,.05,.06)
        end
        if s.roomHover>.01 then s:drawRectBorder(0,0,s.width,s.height,s.roomHover*.46,.58,.78,.78) end
        if s.roomActive and s.roomTile~="chest" then s:drawRect(0,4,2,s.height-8,.92,.875,.620,0) end
        if s.tooltip then s:updateTooltip() end
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
            local press=s.pressed and enabled and 1 or 0
            featureIcon(s,s.roomTile,9,(s.height-22)/2+press,22,a)
            local cy=math.floor(s.height/2)+press
            local toggle=s.roomTile=="chest"
            textInSlot(s,s.roomTitle,40,cy-12,24,s.width-40-(toggle and 50 or 27),.91,.94,.93,
                a*(s.roomEnterAlpha or 1),nil,UIFont.Medium)
            if toggle then
                local x=s.width-41
                s:drawRect(x,cy-7,32,14,1,.14,s.roomActive and .36 or .19,s.roomActive and .34 or .20)
                s:drawRectBorder(x,cy-7,32,14,s.roomActive and .88 or .50,.47,.69,.68)
                s:drawRect(x+(s.roomActive and 18 or 3),cy-5,11,10,1,s.roomActive and .76 or .44,s.roomActive and .91 or .52,s.roomActive and .86 or .53)
            else
                chevron(s,s.width-14,cy,"right",14,.52+s.roomHover*.45)
            end
            return
        end
        if s.roomLook=="outfit" then
            local icon=image(art.."clothes.png")
            if icon then s:drawTextureScaled(icon,9,(s.height-20)/2,20,20,a,.86,.90,.90) end
            label(s,s.roomTitle,34,(s.height-17)/2,17,s.width-40,.90,.93,.92,a)
            return
        end
        if s.roomLook=="quiet" then
            textInSlot(s,s.roomTitle,s.width/2,(s.height-16)/2,16,s.width-16,.77,.83,.83,a*(s.roomEnterAlpha or 1),.5)
            return
        end
        if s.roomWrappedTitle then
            local step=descriptionHeight()
            local top=(s.height-#s.roomWrappedTitle*step)/2
            local tint=.94
            for i,line in ipairs(s.roomWrappedTitle) do
                s:drawTextCentre(line,s.width/2,top+(i-1)*step,tint,tint,tint,a,UIFont.Small)
            end
            return
        end
        if s.roomLook=="parts" or s.roomLook=="drawOnce" or s.roomLook=="drawTen" then
            local t=enabled and .95 or .63
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
    self.fullWidth=self.width;self.fullHeight=self.height;self.roomPage="appearance"
    self.closeButton:setX(18);self.closeButton:setY(6)
    self.closeButton:setWidth(42);self.closeButton:setHeight(38)
    self.closeButton.onclick=function(w)w:back()end
    self.closeButton.prerender=function(b)surface(b,0,0,b.width,b.height,b.mouseOver and .99 or .86)end
    self.closeButton.render=function(b)chevron(b,b.width/2,b.height/2,"left",28,1)end
    self.collapseButton:setVisible(false);self.pinButton:setVisible(false);self.infoButton:setVisible(false)
    self.preview=ISPanel:new(math.floor(self.width*.5-390),50,780,self.height-100);self.preview:initialise();self.preview.background=false
    self.preview.render=function(p)
        local w=p.parent;local ok=false
        if w.roomPage~="appearance" then return end
        if w.tab=="grip" then
            local width,height=math.floor(p.width),math.floor(p.height)
            surface(p,0,0,width,height,.82)
            if not GFL2ReadyRoom.drawGripView(w.player,math.floor(p:getAbsoluteX()),math.floor(p:getAbsoluteY()),width,height,w.gripAiming==true,w.gripYaw,w.gripPitch,w.gripZoom) then
                label(p,tr("NoPreview"),width/2,height/2,17,width-24,.83,.87,.85,1,true)
            end
            return
        end
        local character=w.byCharacter and w.byCharacter[w.character]
        if w.roomPage=="appearance" and character and character.locked then
            local y=math.floor(p.height*.47)
            surface(p,math.floor(p.width*.13),y,math.floor(p.width*.74),62,.91)
            lock(p,math.floor(p.width*.18),y+21)
            local title=w.multiplayerSession and getText("ContextMenu_GFL2_NotOnServer") or tr("CharacterPackMissing")
            local detail=w.multiplayerSession and getText("ContextMenu_GFL2_AskHost") or tr("CharacterPackWorkshop")
            label(p,title,p.width*.53,y+11,19,p.width*.58,.97,.97,.94,1,true)
            label(p,detail,p.width*.53,y+36,15,p.width*.58,.79,.85,.84,1,true)
            return
        end
        local x,y=math.floor(p:getAbsoluteX()),math.floor(p:getAbsoluteY())
        local width,height=math.floor(p.width),math.floor(p.height)
        local now=getTimestampMs()
        local locked=w.roomPage=="appearance" and character and character.unlockRequired==true
        if w.browsed and now>=w.previewAt then w.previewRequestId=w.browsed.id end
        if w.browsed and w.previewRequestId then
            local draw=w.tab=="overview" and GFL2ReadyRoom.drawOverviewCamera or GFL2ReadyRoom.drawBrowseCamera
            ok=draw(w.player,w.previewRequestId,x,y,width,height,w.yaw,w.zoom,w.cameraPan,w.cameraFocus,locked==true)
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
    self.preview.onMouseDown=function(p,x,y)
        if p.parent.tab=="grip" and p.parent.roomPage=="appearance" then
            p.gripDrag=true
            p:setCapture(true)
            return true
        end
        local character=p.parent.byCharacter and p.parent.byCharacter[p.parent.character]
        if character and character.locked then return true end
        p.dragging=true;p.parent.cameraTransition=nil;p:setCapture(true);return true
    end
    self.preview.onMouseMove=function(p,dx,dy)
        local w=p.parent
        if p.gripDrag then
            w.gripYaw=(w.gripYaw+dx*.008)%(2*math.pi)
            w.gripPitch=clamp(w.gripPitch+dy*.008,-.95,.95)
            return true
        end
        if not p.dragging then return false end
        w.yaw=w.yaw+dx*.008
        w.cameraPan=clamp(w.cameraPan+dy/(p.height*w.zoom),-.8,.8)
        return true
    end
    self.preview.onMouseMoveOutside=self.preview.onMouseMove
    self.preview.onMouseUp=function(p)p.dragging=false;p.gripDrag=nil;p:setCapture(false);return true end
    self.preview.onMouseUpOutside=self.preview.onMouseUp
    self.preview.onMouseWheel=function(p,d)
        local w=p.parent
        if w.tab=="grip" and w.roomPage=="appearance" then
            w.gripZoom=clamp(w.gripZoom*(d>0 and .89 or d<0 and 1.12 or 1),.62,4.2)
            return true
        end
        w.cameraTransition=nil
        w.zoom=clamp(w.zoom*(d>0 and .89 or d<0 and 1.12 or 1),.62,4.2)
        return true
    end
    self:addChild(self.preview)
    self.workshopButton=button(self.preview,0,0,256,tr("RoomWorkshopOpen"),self,function(w)w:requestWorkshopPage()end)
    self.workshopButton:setHeight(43);self.workshopButton.roomLook="primary"
    self.workshopButton:setVisible(false)
    self.railWidth=115;self.gridWidth=math.min(365,math.floor(self.width*.255))
    local rightEdge=self.width-math.floor(self.width*.037)
    self.rightWidth=math.max(270,math.floor(self.width*.30));self.rightX=rightEdge-self.rightWidth
    self.overviewWidth=clamp(math.floor(self.width*.225),248,320);self.overviewX=rightEdge-self.overviewWidth
    self.overviewY=math.floor(self.height*.157)
    local railY=math.floor(self.height*.105)
    self.sidebar=ISScrollingListBox:new(math.floor(self.width*.028),railY,self.railWidth,math.floor(self.height*.80));self.sidebar:initialise()
    self.sidebar.itemheight=98;self.sidebar.backgroundColor={r=.025,g=.045,b=.055,a=0};self.sidebar.borderColor.a=0
    self.sidebar:setOnMouseDownFunction(self,function(w,row)
        w:onCharacter(w.rosterExpanded and row[1] or row)
    end)
    self.sidebar.doDrawItem=self.drawCharacter
    -- Dragging the rail scrolls; a short click selects only on release.
    self.sidebar.onMouseDown=function(s,x,y)
        if s:isMouseOverScrollBar() then return end
        s.roomDrag=0;s.roomDown=true;s.roomPressed=s.parent:rosterAt(x,y)
        s:setCapture(true)
        return true
    end
    self.sidebar.onMouseMove=function(s,dx,dy)
        if s.roomDown then
            s.roomDrag=s.roomDrag+math.abs(dx)+math.abs(dy)
            if s.roomDrag>5 then
                s.roomHoverCharacter=nil;s.smoothScrollTargetY=nil
                s:setYScroll(clamp(s:getYScroll()+dy,math.min(0,s.height-s:getScrollHeight()),0))
            end
        else
            s.roomHoverCharacter=not s:isMouseOverScrollBar() and s.parent:rosterAt(s:getMouseX(),s:getMouseY()) or nil
            ISScrollingListBox.onMouseMove(s,dx,dy)
        end
    end
    self.sidebar.onMouseMoveOutside=function(s,dx,dy)
        s.roomHoverCharacter=nil
        if s.roomDown then s:onMouseMove(dx,dy) else ISScrollingListBox.onMouseMoveOutside(s,dx,dy) end
    end
    self.sidebar.onMouseUp=function(s,x,y)
        local pressed=s.roomDown and s.roomDrag<=5 and s.roomPressed
        s.roomDown=false;s.roomPressed=nil;s:setCapture(false)
        if pressed then
            local character,row=s.parent:rosterAt(x,y)
            if pressed==character then s.selected=row;s.parent:onCharacter(character) end
        end
        ISScrollingListBox.onMouseUp(s,x,y)
        return true
    end
    self.sidebar.onMouseUpOutside=function(s,x,y)
        s.roomDown=false;s.roomPressed=nil;s:setCapture(false)
        ISScrollingListBox.onMouseUpOutside(s,x,y)
    end
    self:addChild(self.sidebar)
    self.rosterToggle=button(self,self.sidebar.x+self.sidebar.width,math.floor(self.height*.465),28,"",self,self.toggleRoster)
    self.rosterToggle:setHeight(47)
    self.rosterToggle.roomArrow="right"
    self.rosterToggle.roomLook="navigation"
    self.rosterToggle:setSound("activate","GFL2RoomPage")
    self.content=panel(self,self.rightX,105,self.rightWidth,self.height-247)
    local drawContent=self.content.prerender
    self.content.prerender=function(p)
        drawContent(p)
        if self.tab=="weaponDraw" and self.signatureWeapon then
            local width=p.weaponTextWidth
            textInSlot(p,getText(self.signatureWeapon.name),12,7,34,width,.97,.97,.95,1,nil,UIFont.Large)
            p:drawRect(12,47,width,1,.25,.66,.76,.76)
            description(p,p.weaponLines,12,56,width)
        end
    end
    local biography=panel(self,self.overviewX,self.overviewY,self.overviewWidth,1)
    biography.prerender=function(p)
        p:setStencilRect(0,0,p.width,p.height)
        description(p,p.lines,14,8,p.textWidth)
    end
    biography:setVisible(false)
    self.biographyPanel=biography
    self.pageButton=button(self,0,0,136,tr("RoomPartSwitch"),self,function(w)w:setTab("parts")end)
    self.pageButton.roomLook="parts"
    self.gripButton=button(self,0,0,86,tr("GripCalibration"),self,function(w)w:setTab("grip")end)
    self.gripAimButton=button(self,self.rightX,54,self.rightWidth,getText("ContextMenu_GFL2_GripAim"),self,function(w)
        w.gripAiming=not w.gripAiming
        w:refreshControls(true)
    end)
    self.gripAimButton:setVisible(false)
    self.expressionButton=button(self,0,0,86,tr("Expression"),self,function(w)w:setTab("expression")end)
    self:createExpressionFooter()
    self.skinButton=button(self,0,0,108,tr("RoomOutfits"),self,function(w)w:setTab("skins")end)
    self.skinButton.roomLook="outfit"
    self.skinButton:setTooltip(tr("RoomOutfits"))
    self.skinButton:setSound("activate","GFL2RoomPage")
    self.wearButton=button(self,0,0,160,tr("EnableCharacter"),self,function(w)w:applyBrowse()end)
    self.wearButton.roomLook="primary"
    self.wearButton:setSound("activate","GFL2RoomConfirm")
    local renderWear=self.wearButton.render
    self.wearButton.render=function(b)
        if b.roomLook~="outfitPurchase" then return renderWear(b) end
        local price=self.browsed.price
        local x=(b.width-33-#tostring(price)*23*.63)/2
        local icon=image(art.."credits.png")
        if icon then b:drawTextureScaled(icon,x,7,29,29,1,1,1,1) end
        local affordable=economy.balance(self.player,"credits")>=price
        number(b,price,x+33,10,23,affordable and .94 or .97,affordable and .96 or .49,affordable and .92 or .45)
    end
    self.physicsResetButton=button(self,0,0,86,tr("RoomPhysicsShort"),self,self.requestPhysicsReset)
    self.physicsResetButton.roomLook="quiet"
    self.physicsResetButton:setTooltip(tr("PhysicsReset"))
    self.bodyPhysicsButton=button(self,0,0,86,tr("BodyPhysics"),self,self.toggleBodyPhysics)
    self.restoreButton=button(self,0,0,96,tr("Restore"),self,self.onRestore)
    self.restoreButton.roomLook="quiet"
    self.motionTab=button(self,0,0,86,tr("RoomMotion"),self,self.requestMotionPage)
    self.gripButton.roomTile="grip";self.expressionButton.roomTile="expression"
    self.motionTab.roomTile="play";self.bodyPhysicsButton.roomTile="chest"
    self.foldButton=button(self,self.width-53,7,34,"",self,function(w)w:toggleRoomFold()end)
    self.foldButton.roomFold=true;self.foldButton.roomLook="bare"
    self.foldButton:setTooltip(tr("RoomFold"))
    self:createMotionPage()
    self.weaponCard=button(self,0,0,160,"",self,function(w)w:setTab("weaponDraw")end)
    self.weaponCard.prerender=function(b)
        local hover=hoverAmount(b,b.mouseOver or b.joypadFocused)
        frosted(b,self,0,0,b.width,b.height,.86)
        if hover>0 then b:drawRectBorder(0,0,b.width,b.height,hover*.55,.65,.78,.78) end
    end
    self.weaponCard.render=function(b)
        local signatureWeapon=self.signatureWeapon
        local owned=economy.weaponProgress(self.player,signatureWeapon.id).acquired
        local icon=image(signatureWeapon.card)
        if icon then
            local bounds=signatureWeapon.cardBounds
            local sx,sy=bounds and bounds[1] or 0,bounds and bounds[2] or 0
            local sw,sh=bounds and bounds[3] or icon:getWidth(),bounds and bounds[4] or icon:getHeight()
            local scale=math.min((b.width-64)/sw,(b.height-48)/sh)
            local width,height=sw*scale,sh*scale
            b:drawSubTexture(icon,sx,sy,sw,sh,(b.width-width)/2,(b.height-height)/2,width,height,owned and 1 or .64,1,1,1)
        end
        label(b,getText(signatureWeapon.name),12,b.height-22,16,b.width-48,.94,.95,.94)
        if not owned then lock(b,b.width-30,7) end
        chevron(b,b.width-14,b.height/2+8,"right",17,.72)
    end
    self.weaponCard:setVisible(false)
    self.weaponClaimButton=button(self,0,0,160,getText("ContextMenu_GFL2_ClaimWeapon"),self,function(w)w:claimSignatureWeapon()end)
    self.weaponClaimButton.roomLook="primary"
    self.weaponClaimButton:setHeight(43)
    self.weaponClaimButton:setVisible(false)
    self.magazineClaimButton=button(self,0,0,152,getText("ContextMenu_GFL2_GetMagazine"),self,function(w)w:claimSignatureMagazine()end)
    self.magazineClaimButton.roomLook="drawOnce"
    self.magazineClaimButton:setHeight(43)
    self.magazineClaimButton:setVisible(false)
    self.weaponSkinButton=button(self,0,0,65,getText("ContextMenu_GFL2_WeaponSkins"),self,function(w)
        w.weaponSkinOpen=not w.weaponSkinOpen
        w:layoutOverview()
    end)
    self.weaponSkinButton.roomLook="drawOnce"
    self.weaponSkinButton:setHeight(43)
    self.weaponSkinButton:setVisible(false)
    self.weaponSkinPanel=panel(self,0,0,230,100)
    self.weaponSkinPanel:setVisible(false)
    self.weaponSkinRows={}
    self:createRoomResources()
    self.widgets={};self.tab="overview";self.previewAt=0;self:roomVisibility();self:refresh()
end

local resourceTitles={targeted="RoomTargetedPermit",outfit="RoomOutfitPermit",credits="RoomCredits"}

local function resourceIcon(p,key,x,y,size)
    local t=image(art..key..".png")
    if t then p:drawTextureScaled(t,x,y,size,size,1,1,1,1) end
end

local function bindResourceHold(window,add,action)
    local down,up,outside,moveOutside,update=add.onMouseDown,add.onMouseUp,add.onMouseUpOutside,add.onMouseMoveOutside,add.update
    local function eligible(b)
        return b.enable and b:getIsVisible() and b:isMouseOver() and window.roomPage=="appearance"
            and not window.closed and not window.folded and not window.foldStart
            and not window.exchange and not window.workshopPrompt and not window.drawAnimation
    end
    local function complete(b)
        if not b.resourceHold or getTimestampMs()-b.resourceHold<10000 then return end
        b.resourceHold=nil
        b.resourceHoldConsumed=true
        action(window)
    end
    add.onMouseDown=function(b,x,y)
        down(b,x,y)
        if b.pressed and eligible(b) and not b.resourceHold and not b.resourceHoldConsumed then
            b.resourceHold=getTimestampMs()
        end
    end
    add.onMouseUp=function(b,x,y)
        if eligible(b) then complete(b) end
        b.resourceHold=nil
        if b.resourceHoldConsumed then b.resourceHoldConsumed=nil;b.pressed=false;return end
        return up(b,x,y)
    end
    add.onMouseUpOutside=function(b,x,y)
        b.resourceHold=nil;b.resourceHoldConsumed=nil
        return outside(b,x,y)
    end
    add.onMouseMoveOutside=function(b,dx,dy)
        b.resourceHold=nil
        return moveOutside(b,dx,dy)
    end
    add.update=function(b)
        update(b)
        if not b.resourceHold then return end
        if not eligible(b) or not isMouseButtonDown(0) then b.resourceHold=nil
        else complete(b) end
    end
end

function W:createRoomResources()
    self.resourceButtons={}
    local width=math.min(130,math.floor(self.width*.105))
    local start=self.width-60-3*(width+9)
    for i,key in ipairs(economy.keys)do
        local chip=ISPanel:new(start+(i-1)*(width+9),8,width,34)
        chip:initialise();chip.background=false
        local add
        chip.prerender=function(p)
            p:drawRect(0,0,p.width,p.height,.42,.075,.11,.12)
            p:drawRect(p.width-26,7,1,p.height-14,.32,.58,.69,.70)
            resourceIcon(p,key,2,1,32)
            local balance=economy.balance(self.player,key)
            local count=#string.format("%.0f",balance)
            local size=math.min(17,(p.width-62)/(count*.63))
            number(p,balance,p.width-29,(p.height-size)/2,size,.95,.96,.95,true)
            local started=add.resourceHold or add.creditHold
            local progress=0
            if add.resourceHoldConsumed or add.creditGranted then progress=1
            elseif started then progress=clamp((getTimestampMs()-started)/(key=="credits" and 8000 or 10000),0,1) end
            if progress>=.05 then
                p:drawRect(2,p.height-2,p.width-4,2,.40,.37,.47,.48)
                p:drawRect(2,p.height-2,(p.width-4)*progress,2,.92,.58,.82,.77)
            end
        end
        self:addChild(chip)
        add=button(chip,width-26,0,26,"+",self,function(w)w:openExchange(key)end)
        add:setHeight(34);add:setTooltip(tr(resourceTitles[key]))
        add.roomLook="bare"
        add.render=function(b)
            b:drawRect(7,16,12,2,1,.86,.91,.91)
            b:drawRect(12,11,2,12,1,.86,.91,.91)
        end
        if key=="targeted" then
            bindResourceHold(self,add,function(w)
                if motionEntryVisible(w) then motionEntryPlayers[w.playerIndex]=nil
                else motionEntryPlayers[w.playerIndex]=w.player end
                w:layoutOverview()
            end)
        elseif key=="outfit" and not self.multiplayerSession then
            bindResourceHold(self,add,function(w)
                if economy.toggleRecordingUnlocks(w.player)~=nil then w:refresh() end
            end)
        elseif key=="credits" and not self.multiplayerSession then
            local originalDown,originalUp,originalOutside=add.onMouseDown,add.onMouseUp,add.onMouseUpOutside
            add.onMouseDown=function(b,x,y)
                originalDown(b,x,y)
                if b.enable and b:getIsVisible() and not b.creditHold and not b.creditGranted then b.creditHold=getTimestampMs() end
            end
            add.onMouseUp=function(b,x,y)
                b.creditHold=nil
                if b.creditGranted then b.creditGranted=false;b.pressed=false;return end
                return originalUp(b,x,y)
            end
            add.onMouseUpOutside=function(b,x,y)
                b.creditHold=nil
                b.creditGranted=false
                return originalOutside(b,x,y)
            end
            local originalUpdate=add.update
            add.update=function(b)
                originalUpdate(b)
                if not b.creditHold then return end
                if not b:isMouseOver() or not isMouseButtonDown(0) or self.closed or self.folded or self.foldStart or self.exchange then
                    b.creditHold=nil
                elseif getTimestampMs()-b.creditHold>=8000 then
                    b.creditHold=nil
                    b.creditGranted=economy.grantTestCredits(self.player)
                    if b.creditGranted then self.player:Say(getText("ContextMenu_GFL2_TestCredits")) end
                end
            end
            self.creditAddButton=add
        end
        self.resourceButtons[i]=chip
    end
    self.drawButtons={}
    for i,key in ipairs({"RoomDrawOnce","RoomDrawTen"})do
        local count=i==1 and 1 or 10
        local width=math.floor(self.width*.137)
        local right=math.floor(self.width*.963)
        local x=right-width*2-19+(i-1)*(width+19)
        local b=button(self,x,math.floor(self.height*.919),width,tr(key),self,function(w)
            if w.tab=="outfitDraw" then w:requestOutfitDraw(count)
            elseif w.tab=="weaponDraw" then w:requestWeaponDraw(count)
            else w:requestCharacterDraw(count) end
        end)
        b:setHeight(43);b.roomLook=i==1 and "drawOnce" or "drawTen"
        b:setSound("activate","GFL2RoomDrawStart")
        local draw=b.render
        b.render=function(p)
            draw(p)
            surface(p,0,-29,p.width,25,.79)
            local anim=self.drawAnimation
            if anim and self.drawButtons[anim.button]==p and anim.soundIndex and anim.results[anim.soundIndex]==false then return end
            resourceIcon(p,p.roomOutfitDraw and "outfit" or "targeted",p.width/2-34,-32,29)
            label(p,"×",p.width/2-4,-24,15,12)
            number(p,count,p.width/2+9,-24,15,.96,.96,.95)
        end
        b:setEnable(false);b:setVisible(false)
        self.drawButtons[i]=b
    end
end

local drawStep,missLife,hitLife=215,205,1180

-- Single-player settles immediately. In multiplayer only a server reply can start the animation.
function W:roomTransaction(command,target,count,localAction,onResult)
    if not self.multiplayerSession then
        onResult(localAction())
        return
    end
    if multiplayer.pending(self.player) then return end
    local sent=multiplayer.transact(self.player,command,target,count,function(result)
        if self.closed then return end
        onResult(result.status,result.results,result.misses)
    end)
    if not sent then self.drawNotice=getText("ContextMenu_GFL2_Connecting") end
end

function W:requestCharacterDraw(count)
    local character=self.byCharacter[self.character]
    local card=character and character.recruitment
    if self.drawAnimation or self.exchange or not card or character.locked
        or not character.unlockRequired or self.tab~="overview" then return end
    local target=character.baseSkin or character.skins[1]
    if not target then return end
    local function localDraw()
        local misses=economy.characterMisses(self.player)
        local status,results=economy.drawCharacter(self.player,card.pool,count)
        return status,results,misses
    end
    self:roomTransaction("DrawCharacter",target.id,count,localDraw,function(status,results,misses)
        if status=="short" then self.drawNotice=tr("RoomDrawShort");return end
        if not results then return end
        self.drawNotice=nil
        self.drawAnimation={started=getTimestampMs()+140,results=results,won=status=="unlocked",
            character=character.name,rewardName=roleNames[character.name] or character.name,portrait=character.portrait,misses=misses,button=count==1 and 1 or 2}
        self:layoutOverview()
    end)
end

function W:requestOutfitDraw(count)
    local character=self.byCharacter[self.character]
    local entry=self.browsed
    if self.drawAnimation or self.exchange or self.tab~="outfitDraw" or not character or character.unlockRequired
        or not entry or not entry.outfitLocked or not entry.outfitPool or entry.id==self.currentId then return end
    local function localDraw()
        local misses=economy.outfitMisses(self.player,entry.id)
        local status,results=economy.drawOutfit(self.player,entry.id,count)
        return status,results,misses
    end
    self:roomTransaction("DrawOutfit",entry.id,count,localDraw,function(status,results,misses)
        if status=="short" then self.drawNotice=tr("RoomOutfitDrawShort");return end
        if not results then return end
        self.drawNotice=nil
        self.drawAnimation={started=getTimestampMs()+140,results=results,won=status=="unlocked",outfit=entry.id,
            rewardName=outfitName(entry),portrait=entry.meta.portrait,misses=misses,button=count==1 and 1 or 2}
        self:layoutOverview()
    end)
end

function W:requestWeaponDraw(count)
    if self.tab~="weaponDraw" or self.exchange or self.drawAnimation then return end
    local signatureWeapon=self.signatureWeapon
    if not signatureWeapon then return end
    local previousMisses=economy.weaponMisses(self.player)
    self:roomTransaction("DrawWeapon",signatureWeapon.id,count,function()
        return economy.drawWeapon(self.player,signatureWeapon,count)
    end,function(status,results)
        if status=="short" then self.drawNotice=tr("RoomDrawShort");return end
        if not results then return end
        self.drawNotice=nil
        self.drawAnimation={started=getTimestampMs()+140,results=results,won=status=="unlocked",
            weapon=true,rewardName=getText(signatureWeapon.name),portrait=signatureWeapon.icon,misses=previousMisses,button=count==1 and 1 or 2}
        self:layoutOverview()
    end)
end

function W:claimSignatureWeapon()
    local signatureWeapon=self.signatureWeapon
    if not signatureWeapon or self.tab~="weaponDraw" then return end
    self:roomTransaction("ClaimWeapon",signatureWeapon.id,nil,function()
        return economy.claimWeapon(self.player,signatureWeapon)
    end,function(status)
        if status=="ok" then
            self.drawNotice=nil
            self.player:Say(getText("ContextMenu_GFL2_WeaponClaimed"))
        elseif status=="alreadyCarried" then
            self.drawNotice=getText("ContextMenu_GFL2_WeaponCarried")
        else
            self.drawNotice=getText("ContextMenu_GFL2_WeaponClaimFailed")
        end
        self:layoutOverview()
    end)
end

function W:claimSignatureMagazine()
    local signatureWeapon=self.signatureWeapon
    if not signatureWeapon or self.tab~="weaponDraw" or self.drawAnimation or self.exchange then return end
    self:roomTransaction("ClaimMagazine",signatureWeapon.id,nil,function()
        return economy.claimMagazine(self.player,signatureWeapon)
    end,function(status)
        self.drawNotice=getText(status=="ok" and "ContextMenu_GFL2_MagazineClaimed" or "ContextMenu_GFL2_MagazineClaimFailed")
    end)
end

function W:applyWeaponSkin(skin)
    local weapon=self.signatureWeapon
    if not weapon or self.tab~="weaponDraw" or self.drawAnimation or self.exchange then return end
    self:roomTransaction("WeaponSkin",weapon.id..":"..skin,nil,function()
        return economy.setWeaponSkin(self.player,weapon,skin)
    end,function(status)
        if status=="ok" and self.signatureWeapon==weapon then
            self.drawNotice=nil
            self:layoutOverview()
        end
    end)
end

function W:layoutWeaponSkins(weapon,variants,visible,selected)
    local p=self.weaponSkinPanel
    p:setVisible(visible)
    if not visible then return end
    local width=math.max(160,math.floor(self.width*.205))
    p:setX(math.floor(self.width*.035));p:setY(106);p:setWidth(width)
    if p.weaponId~=weapon.id then
        for _,row in ipairs(self.weaponSkinRows) do p:removeChild(row) end
        self.weaponSkinRows={}
        p.weaponId=weapon.id
        local row=button(p,8,8,width-16,getText("ContextMenu_GFL2_WeaponDefaultSkin"),self,function(w)w:applyWeaponSkin("")end)
        row.skinId="";self.weaponSkinRows[1]=row
        for _,skin in ipairs(variants) do
            local id=skin.id
            local original=data.weaponStyle.skins[id]
            local title=(getTextOrNull and getTextOrNull("UI_GFL2_WeaponSkin_"..id)) or original
            local b=button(p,8,8,width-16,title,self,function(w)w:applyWeaponSkin(id)end)
            b.skinId=id;b.roomLocalizedPaint=title~=original
            self.weaponSkinRows[#self.weaponSkinRows+1]=b
        end
    end
    local function arrange(rowWidth)
        local y=8
        for _,row in ipairs(self.weaponSkinRows) do
            if row.roomLocalizedPaint then row.roomWrappedTitle=describe(row.roomTitle,rowWidth-16) end
            local lines=row.roomWrappedTitle and #row.roomWrappedTitle or 1
            row:setY(y);row:setWidth(rowWidth)
            row:setHeight(math.max(34,lines*descriptionHeight()+12))
            row.roomLook=row.skinId==selected and "primary" or "drawOnce"
            y=y+row.height+5
        end
        return y+8
    end
    local total=arrange(width-16)
    local maxHeight=math.floor((self.fullHeight-p.y)*.5)
    if total>maxHeight then total=arrange(width-30) end
    p:setHeight(math.min(total,maxHeight))
    p:setScrollHeight(total)
    p:setYScroll(clamp(p:getYScroll(),p.height-p:getScrollHeight(),0))
    p:bringToTop()
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
            self.toolNotice=a.weapon and getText("ContextMenu_GFL2_ClaimWeapon") or tr(a.outfit and "RoomOutfitAcquired" or "RoomDrawUnlocked")
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
    local portrait=a.weapon and image(a.portrait) or portraitImage(a.portrait)
    local right=self.drawButtons[2].x+self.drawButtons[2].width
    for i,hit in ipairs(a.results)do
        local age=elapsed-(i-1)*drawStep
        local life=hit and hitLife or missLife
        if age>=0 and age<life then
            if hit then
                local width=right-self.drawButtons[1].x
                local x,y=right-width,button.y-99+4*(1-ease(age/130))
                local alpha=ease(age/120)*clamp((life-age)/160,0,1)
                self:drawRect(x,y,width,58,.94*alpha,.10,.14,.15)
                self:drawRect(x,y,3,58,alpha,.99,.72,.22)
                if portrait then self:drawTextureScaledAspect(portrait,x+7,y+6,46,46,alpha,1,1,1) end
                label(self,tr("RoomDrawHit"),x+63,y+5,14,width-72,1,.76,.33,alpha)
                label(self,a.rewardName or "",x+63,y+25,21,width-72,.96,.97,.95,alpha)
            else
                local alpha=clamp(age/35,0,1)*clamp((life-age)/90,0,1)
                label(self,tr("RoomDrawMiss"),button.x+button.width/2,button.y-20-4*ease(age/life),14,
                    button.width,.73,.79,.79,alpha,true)
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
    self.preview:onMouseUp()
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
        w:roomTransaction("Exchange",key,s.quantity,function()
            return economy.buy(w.player,key,s.quantity) and "ok" or "short"
        end,function(status)
            if w.exchange~=s then return end
            s.message=tr(status=="ok" and "RoomExchangeDone" or "RoomNotEnough")
            w:updateExchange(s.quantity)
        end)
    end)
    s.confirm:setSound("activate","GFL2RoomConfirm")
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
    local slots=self.player:getModData().GFL2Expressions
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
        local snapshot=GFL2ReadyRoom.expressionSnapshot(self.player,self.currentId)
        if snapshot=="" then self.status=tr("Error");return end
        slots[slot]=snapshot
        local saved=self.player:getModData()
        saved.GFL2Expressions=saved.GFL2Expressions or {}
        saved.GFL2Expressions[self.currentId]=slots
        self.expressionSaving=false;self:refreshExpressionSlots(true)
    elseif type(slots[slot])=="string" then
        local error=GFL2ReadyRoom.expressionLoad(self.player,self.currentId,slots[slot])
        if error~="" then print("[GFL2Exilium] "..error);self.status=tr("Error");return end
        self:refreshControls(true)
    else return end
    self.actions.expressionRemember(self.player)
end

local function motionTrack(slider,x,y,width,seek)
    local over=not slider.disabled and slider:isMouseOver() and (not seek or slider:getMouseY()>=13)
    local hover=hoverAmount(slider,over or (not slider.disabled and (slider.dragInside or slider.joypadFocused)))
    local h=slider.dragInside and not slider.disabled and 4 or 3
    slider:drawRect(x,y,width,h,.92,.15+.07*hover,.21+.08*hover,.24+.08*hover)
    if slider.disabled then return end
    local fill=width*clamp(slider.currentValue,0,1)
    if fill>0 then slider:drawRect(x,y,fill,h,1,seek and .94 or .53,seek and .71 or .77,seek and .38 or .73) end
    if seek and over and not slider.dragInside then
        local tick=clamp(slider:getMouseX(),x,x+width-1)
        slider:drawRect(tick,y-3,1,h+6,.76*hover,.85,.90,.88)
    end
    local knob=image(art.."knob.png")
    local size=slider.dragInside and 12 or 8+2*hover
    if knob then slider:drawTextureScaled(knob,x+fill-size/2,y+(h-size)/2,size,size,1,
        seek and 1 or .76,seek and .80 or .92,seek and .52 or .87) end
end

function W:createMotionPage()
    self.motionPanelWidth=math.min(550,self.fullWidth)
    self.motionPanelHeight=math.min(450,self.fullHeight)
    local height=self.motionPanelHeight-64
    local listWidth=math.floor((self.motionPanelWidth-32)*.45)
    local library=ISPanel:new(12,54,listWidth,height)
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
        local scroll=s:getYScroll()
        if y+row.height+scroll<=0 or y+scroll>=s.height then return y+row.height end
        local hover=hoverAmount(row,s.mouseoverselected==row.index and s:isMouseOver() and not s:isMouseOverScrollBar())
        if row.index==s.selected then
            s:drawRect(0,y,s.width-14,row.height-2,.93,.15+.04*hover,.19+.05*hover,.20+.05*hover)
            s:drawRect(0,y+4,2,row.height-10,1,1,.71,.38)
        else
            if alt then s:drawRect(0,y,s.width-14,row.height-2,.42,.055,.085,.105) end
            if hover>0 then s:drawRect(0,y,s.width-14,row.height-2,.76*hover,.18,.28,.30) end
        end
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

    local box=ISPanel:new(20+listWidth,54,self.motionPanelWidth-32-listWidth,height)
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
        label(p,tr("MotionSpeed"),12,118,15,textWidth-66,.65,.73,.77)
        textInSlot(p,string.format("%.2fx",w.motionSpeed.currentValue),p.width-12,118,15,60,.82,.88,.90,1,1)
        p:drawRect(12,162,textWidth,1,.28,.41,.55,.60)
        label(p,tr("MotionSelected"),12,171,14,textWidth,.65,.73,.77)
        label(p,w.motionSelectedLabel or tr("MotionNoSelection"),12,189,16,textWidth)
        p:drawRect(12,284,textWidth,1,.28,.41,.55,.60)
        label(p,tr("MotionPhysics"),12,293,16,textWidth,.65,.73,.77)
        if w.motionMessage then
            label(p,w.motionMessage,12,348,17,textWidth,1,.74,.42)
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
    self.motionButtons.play.roomLook="primary"
    for _,key in ipairs({"play","pause","restart","stop"})do self.motionButtons[key].roomIcon=key end
    self:createMotionTimeline(box,width)
    local speed=ISSliderPanel:new(12,135,width,21,self,function(w,value)
        if math.abs(value-(w.motionPlayback.speed or 1))<.0001 then return end
        w:onMotionCommand("speed",tostring(value))
    end)
    speed:initialise();speed:instantiate()
    speed:setDoButtons(false);speed:setValues(0,1,.01,.1,true)
    speed.sliderBarDim.x=6;speed.sliderBarDim.w=width-12
    speed.currentValue=1;speed.disabled=true
    local speedDown=speed.onMouseDown
    speed.onMouseDown=function(s,x,y)
        if not s.disabled then return speedDown(s,x,y) end
    end
    speed.render=function(s)motionTrack(s,s.sliderBarDim.x,math.floor(s.height/2)-1,s.sliderBarDim.w,false)end
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
    slider.sliderBarDim.x=6;slider.sliderBarDim.w=width-12
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
        local error=GFL2ReadyRoom.motionCommand(w.player,"seek",revision..":"..tostring(fraction))
        w.motionMessage=error~="" and error or nil;w:refreshMotionControls()
        return true
    end
    slider.onMouseUpOutside=slider.onMouseUp
    slider.render=function(s)
        local state=s.target.motionPlayback
        local duration=state and state.endFrame or 0
        local frame=s.dragInside and duration*s.currentValue or state and state.frame or 0
        motionTrack(s,s.sliderBarDim.x,25,s.sliderBarDim.w,true)
        local current=math.floor(frame/30)
        local total=math.floor(duration/30)
        if current~=s.lastSecond then s.lastSecond=current;s.clock=clockText(frame) end
        if total~=s.lastDuration then s.lastDuration=total;s.total=clockText(duration) end
        textInSlot(s,s.disabled and "--:--" or s.clock,0,0,18,s.width/2,.82,.88,.90,1)
        textInSlot(s,s.disabled and "--:--" or s.total,s.width,0,18,s.width/2,.58,.67,.71,1,1)
    end
    box:addChild(slider);self.motionSeek=slider
end

function W:refreshMotionLibrary()
    local files=GFL2ReadyRoom.motionLibrary()
    self.motionList:clear()
    local selected=nil
    for i=1,#files do
        local file=files[i]
        local row=self.motionList:addItem(motionDisplayName(file),file,
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
            if removing then error=GFL2ReadyRoom.removeMotionPath(path)
            else error=GFL2ReadyRoom.addMotionPath(path).error end
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
    local state=GFL2ReadyRoom.motionState(self.player)
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
        self.motionPlayingLabel=active and motionDisplayName(state.name) or nil
    end
    if self.motionSelectionFile~=self.motionSelected then
        self.motionSelectionFile=self.motionSelected
        self.motionSelectedLabel=self.motionSelected and motionDisplayName(self.motionSelected) or nil
    end
    b.play:setEnable(self.motionSelected~=nil and not self.waiting and GFL2CharacterRuntime.isEnabled(self.player))
    for _,key in ipairs({"pause","restart","stop","physics","reset"})do b[key]:setEnable(active and not self.waiting)end
    b.reset:setEnable(active and state.physics and not self.waiting)
    b.pause.roomTitle=tr(state.paused and "MotionResume" or "MotionPause")
    b.pause.roomActive=active and state.paused
    b.pause.roomIcon=state.paused and "play" or "pause"
    b.physics.roomTitle=tr(state.physics and "MotionPhysicsOn" or "MotionPhysicsOff")
end

function W:onMotionCommand(command,value)
    local error=GFL2ReadyRoom.motionCommand(self.player,command,value or self.motionSelected or "")
    self.motionMessage=error~="" and error or nil;self:refreshMotionControls()
end

function W:layoutPreview()
    if self.roomPage=="motions" then return end
    if self.tab=="grip" then
        self.preview:setX(18);self.preview:setWidth(self.rightX-32)
        self.preview:setY(57);self.preview:setHeight(self.fullHeight-77)
    else
        self.preview:setX(math.floor(self.width*.23));self.preview:setWidth(math.floor(self.width*.54))
        self.preview:setY(math.floor(self.fullHeight*.08));self.preview:setHeight(math.floor(self.fullHeight*.86))
    end
end

function W:roomVisibility()
    local expanded=not self.folded and not self.foldStart
    local motions=expanded and self.roomPage=="motions"
    if not motions then self.motionSpeed.dragInside=false end
    local appearance=expanded and not motions
    self.preview:setVisible(appearance and self.tab~="weaponDraw")
    if self.weaponPreview then
        self.weaponPreview:onMouseUp()
        self.weaponPreview:setVisible(false)
    end
    self:layoutPreview()
    self.gripAimButton:setVisible(false)
    self:refreshExpressionSlots(false)
    for _,widget in ipairs({self.sidebar,self.rosterToggle,self.content,self.pageButton,self.gripButton,
        self.expressionButton,self.skinButton,self.wearButton,self.physicsResetButton,self.bodyPhysicsButton,self.restoreButton,self.motionTab,self.workshopButton,self.weaponCard,self.weaponClaimButton,self.magazineClaimButton,self.weaponSkinButton,self.weaponSkinPanel}) do
        widget:setVisible(false)
    end
    self.motionLibraryBox:setVisible(motions);self.motionBox:setVisible(motions)
    for _,b in ipairs(self.resourceButtons)do b:setVisible(appearance) end
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
    if page=="motions" and not motionEntryVisible(self) then return end
    self:closeWorkshopPrompt()
    self:closeMotionPathDialog()
    self.motionSeek:cancel()
    self:closeExchange()
    if page=="motions" then
        GFL2ReadyRoom.release(self.player)
        self.appearanceX,self.appearanceY=self:getX(),self:getY()
        self:setX(self.appearanceX+self.fullWidth-self.motionPanelWidth)
        self:setWidth(self.motionPanelWidth)
        self:setHeight(self.motionPanelHeight)
    else
        self:setX(self.appearanceX);self:setY(self.appearanceY)
        self:setWidth(self.fullWidth);self:setHeight(self.fullHeight)
    end
    self:clearContent()
    self.preview:onMouseUp()
    self.sidebar.roomDown=false;self.sidebar:setCapture(false)
    self.gripAiming=false
    self:endTabMotion()
    self.roomPage=page;self.foldButton:setX(self.width-53);self:roomVisibility()
    if page=="motions" then self:refreshMotionLibrary() else self:refreshControls() end
end

function W:toggleRoomFold()
    if self.drawAnimation then return end
    self:closeWorkshopPrompt()
    self:closeExchange()
    self:closeMotionPathDialog()
    self.motionSeek:cancel()
    self:endTabMotion()
    self.folded=not self.folded
    self.preview:onMouseUp()
    self.sidebar.roomDown=false;self.sidebar:setCapture(false)
    if self.folded then
        self.gripAiming=false
        self:clearContent()
        GFL2ReadyRoom.release(self.player)
    end
    self.foldFrom=self.height;self.foldStart=getTimestampMs()
    local height=self.roomPage=="motions" and self.motionPanelHeight or self.fullHeight
    self.foldTo=self.folded and self:titleBarHeight() or height
    self.foldButton:setTooltip(tr(self.folded and "RoomUnfold" or "RoomFold"))
    self:roomVisibility()
end

function W:layoutWeaponDescription(weapon)
    local p=self.content
    local x=math.floor(self.fullWidth*.045)-12
    local width=math.floor(self.fullWidth*.32)+24
    local top=self:titleBarHeight()+12
    if self.weaponSkinPanel:getIsVisible() then top=self.weaponSkinPanel.y+self.weaponSkinPanel.height+8 end
    local scene=self.weaponPreview
    if x+width+8>scene.x then top=math.max(top,scene.y+scene.height+8) end
    local bottom=self.fullHeight-14
    local textWidth=width-24
    local lines=describe(getText(weapon.description),textWidth)
    if 65+#lines*descriptionHeight()>bottom-top then
        textWidth=textWidth-16
        lines=describe(getText(weapon.description),textWidth)
    end
    local total=65+#lines*descriptionHeight()
    local y=math.max(top,bottom-total)
    p:setX(x);p:setY(y);p:setWidth(width);p:setHeight(math.min(total,bottom-y))
    p:setScrollHeight(total);p:setYScroll(clamp(p:getYScroll(),p.height-total,0))
    p.weaponLines,p.weaponTextWidth=lines,textWidth
end

function W:createWeaponPreview(signatureWeapon)
    if self.weaponPreview then
        self.weaponPreview:onMouseUp()
        self:removeChild(self.weaponPreview)
    end
    local side=math.floor(self.fullWidth*.035)+math.max(160,math.floor(self.fullWidth*.205))+8
    local bottom=self.drawButtons[1].y-110
    -- UI3DScene normalizes its projection to the viewport width. A square keeps the
    -- authored full-weapon framing valid when a long barrel rotates towards vertical.
    local size=math.min(self.fullWidth-2*side,2*bottom-self.fullHeight)
    local scene=ISUI3DScene:new(math.floor((self.fullWidth-size)/2),math.floor((self.fullHeight-size)/2),size,size)
    scene.background=false
    scene:initialise()
    scene:instantiate()
    local java=scene.javaObject
    local id=signatureWeapon.id
    local model=weaponSkins.model(id,economy.weaponProgress(self.player,id).skin) or id
    local script=getScriptManager():getModelScript(model)
    local muzzle=script and script:getAttachmentById("muzzle")
    -- The muzzle's local roll also describes which side of the barrel is up.
    local tilt=muzzle and (180-muzzle:getRotate():z())%360 or 0
    scene:setView("UserDefined")
    java:fromLua3("setViewRotation",-82+tilt,0,90)
    java:fromLua1("setDrawGrid",false)
    local maxZoom=math.floor(signatureWeapon.previewZoom or 14)
    local zoom=maxZoom
    java:fromLua1("setMaxZoom",maxZoom)
    java:fromLua1("setZoom",zoom)
    java:fromLua2("createModel",id,model)
    local translation=java:fromLua1("getObjectTranslation",id)
    if not GFL2ReadyRoom.centerWeaponPreview(model,translation) then
        scene.prerender=function(p)
            if GFL2ReadyRoom.centerWeaponPreview(model,translation) then p.prerender=nil end
        end
    end
    scene.onMouseWheel=function(_,delta)
        zoom=clamp(zoom-delta,1,maxZoom)
        java:fromLua1("setZoom",zoom)
        return true
    end
    scene.onMouseDown=function(p)
        p.orbit=true
        p:setCapture(true)
        return true
    end
    scene.onMouseMove=function(p,dx,dy)
        if not p.orbit then return false end
        p.pitch=clamp((p.pitch or -82)+dy*.35,-175,-5)
        p.heading=((p.heading or 0)+dx*.35)%360
        java:fromLua3("setViewRotation",p.pitch+tilt,p.heading,90)
        return true
    end
    scene.onMouseMoveOutside=scene.onMouseMove
    scene.onMouseUp=function(p)
        p.orbit=false
        p:setCapture(false)
        return true
    end
    scene.onMouseUpOutside=scene.onMouseUp
    self:addChild(scene)
    scene.weaponId=id
    scene.skinModel=model
    self.weaponPreview=scene
end

function W:layoutOverview()
    self:endTabMotion()
    local overview=self.tab=="overview"
    local skins=self.tab=="skins"
    local character=self.byCharacter and self.byCharacter[self.character]
    local loaded=character~=nil and not character.locked
    local locked=loaded and character.unlockRequired==true
    local available=loaded and not locked
    self.workshopButton:setX(math.floor((self.preview.width-self.workshopButton.width)/2))
    self.workshopButton:setY(math.floor(self.preview.height*.47)+76)
    local workshopItem=character and (data.roomWorkshopItems or {})[character.name]
    self.workshopButton:setVisible(not not (overview and character and character.locked and workshopItem and not self.multiplayerSession))
    local outfitDraw=available and self.tab=="outfitDraw" and self.browsed and self.browsed.outfitLocked
        and self.browsed.outfitPool~=nil
    local characterDraw=overview and locked
    local signatureWeapon=loaded and character.recruitment and signatureWeapons.byCharacter[character.recruitment.pool]
    if signatureWeapon and not getScriptManager():FindItem(signatureWeapon.id) then signatureWeapon=nil end
    local previousWeapon=self.signatureWeapon
    self.signatureWeapon=signatureWeapon
    local weaponPage=signatureWeapon and self.tab=="weaponDraw"
    local progress=signatureWeapon and economy.weaponProgress(self.player,signatureWeapon.id) or {}
    local variants=weaponPage and weaponSkins.available(signatureWeapon.id) or {}
    if previousWeapon~=signatureWeapon or not weaponPage then self.weaponSkinOpen=false end
    local weaponDraw=weaponPage and available and not progress.acquired
    local first,last=self.drawButtons[1],self.drawButtons[2]
    local claimVisible=not not (weaponPage and progress.acquired and not self.drawAnimation)
    local magazineVisible=claimVisible and economy.weaponMagazine(signatureWeapon)~=nil
    local magazineWidth=math.min(first.width,152)
    local claimX=magazineVisible and first.x+magazineWidth+12 or first.x
    self.magazineClaimButton:setX(first.x)
    self.magazineClaimButton:setY(last.y)
    self.magazineClaimButton:setWidth(magazineWidth)
    self.magazineClaimButton:setVisible(magazineVisible)
    self.weaponClaimButton:setX(claimX)
    self.weaponClaimButton:setY(last.y)
    self.weaponClaimButton:setWidth(last.x+last.width-claimX)
    self.weaponClaimButton:setVisible(claimVisible)
    local carried=weaponPage and progress.acquired and economy.hasWeaponInInventory(self.player,signatureWeapon.id)
    self.weaponClaimButton:setEnable(not carried)
    self.weaponClaimButton.roomTitle=getText(carried and "ContextMenu_GFL2_WeaponCarried"
        or progress.claimed and "ContextMenu_GFL2_ReclaimWeapon" or "ContextMenu_GFL2_ClaimWeapon")
    local showSkins=claimVisible and #variants>0
    self.weaponSkinButton:setX(first.x-77)
    self.weaponSkinButton:setY(last.y)
    self.weaponSkinButton:setVisible(showSkins)
    local skin=progress.skin or ""
    local previewModel=weaponPage and (weaponSkins.model(signatureWeapon.id,skin) or signatureWeapon.id)
    self:layoutWeaponSkins(signatureWeapon,variants,showSkins and self.weaponSkinOpen==true,skin)
    if weaponPage and (not self.weaponPreview or self.weaponPreview.weaponId~=signatureWeapon.id
            or self.weaponPreview.skinModel~=previewModel) then
        self:createWeaponPreview(signatureWeapon)
    end
    if weaponPage then self:layoutWeaponDescription(signatureWeapon) end
    if self.weaponPreview then
        if not weaponPage and self.weaponPreview.orbit then self.weaponPreview:onMouseUp() end
        self.weaponPreview:setVisible(not not weaponPage)
    end
    for _,b in ipairs(self.drawButtons)do
        b.roomOutfitDraw=outfitDraw
        b:setVisible(not not (characterDraw or outfitDraw or weaponDraw))
        b:setEnable(not self.drawAnimation and not not (outfitDraw or characterDraw or weaponDraw))
    end
    local x,y,width=self.overviewX,self.overviewY,self.overviewWidth
    local physicsAvailable=available and overview and self:canEditCurrent()
    local inner=width-24
    local rowY=y+54
    for _,b in ipairs({self.gripButton,self.expressionButton,self.motionTab,self.bodyPhysicsButton}) do
        local listed=(b~=self.motionTab or motionEntryVisible(self)) and (b~=self.bodyPhysicsButton or physicsAvailable)
        b:setVisible(not not (listed and available and overview))
        b:setX(x+12);b:setY(rowY);b:setWidth(inner);b:setHeight(35)
        if listed then rowY=rowY+37 end
    end
    local physicsOn=physicsAvailable and GFL2CharacterRuntime.modelPhysicsEnabled(self.player)
    self.bodyPhysicsButton.roomActive=not not physicsOn
    self.bodyPhysicsButton:setTooltip(getText(physicsOn and "ContextMenu_GFL2_BodyPhysicsOn" or "ContextMenu_GFL2_BodyPhysicsOff"))
    local restore=available and overview and GFL2CharacterRuntime.isEnabled(self.player)
    self.physicsResetButton:setVisible(available and overview)
    self.restoreButton:setVisible(restore)
    local half=math.floor((inner-6)/2)
    local tm=getTextManager()
    local zoom=slotFontSize(tm,UIFont.Small,16)
    local stacked=restore and tm:MeasureStringX(UIFont.Small,self.restoreButton.roomTitle)*zoom>half-16
    local split=restore and not stacked
    rowY=rowY+4
    self.physicsResetButton:setX(x+12);self.physicsResetButton:setY(rowY)
    self.physicsResetButton:setWidth(split and half or inner);self.physicsResetButton:setHeight(28)
    self.restoreButton:setX(split and x+18+half or x+12);self.restoreButton:setY(stacked and rowY+30 or rowY)
    self.restoreButton:setWidth(split and inner-half-6 or inner);self.restoreButton:setHeight(28)
    self.biographyPanel:setY(rowY+(stacked and 66 or 36))
    local secondary=math.floor((width-6)*.43)
    local splitFooter=available and (overview or skins)
    self.wearButton:setX(splitFooter and x+secondary+6 or x);self.wearButton:setY(math.floor(self.height*.919))
    self.wearButton:setWidth(splitFooter and width-secondary-6 or width);self.wearButton:setHeight(43)
    self.wearButton:setVisible(available and self.tab~="outfitDraw" and not weaponPage and (overview or skins or not self:canEditCurrent()))
    self.weaponCard:setX(x)
    self.weaponCard:setWidth(width)
    self.weaponCard:setHeight(clamp(math.floor(self.fullHeight*.15),84,132))
    self.weaponCard:setY(self.wearButton.y-self.weaponCard.height-6)
    self.weaponCard:setVisible(not not (signatureWeapon and overview and (available or progress.acquired)))
    self.skinButton:setWidth(secondary);self.skinButton:setHeight(43)
    self.skinButton:setX(x);self.skinButton:setY(self.wearButton.y)
    self.skinButton:setVisible(available and overview)
    self.pageButton:setWidth(secondary);self.pageButton:setHeight(43)
    self.pageButton:setX(x);self.pageButton:setY(self.wearButton.y)
    self.pageButton:setVisible(available and skins)
    if not overview then self.sidebar.roomHoverCharacter=nil end
    self.sidebar:setVisible(overview)
    self.rosterToggle:setVisible(overview)
end

function W:rebuildRoster()
    local rail=self.sidebar
    rail:clear();rail.smoothScrollTargetY=nil;rail.roomHoverCharacter=nil;rail.roomPressed=nil
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
        for i,entry in ipairs(item.item) do
            local x,top,size,height=rosterCard(window,i,y)
            local hover=hoverAmount(entry,self.roomHoverCharacter==entry)
            local selected=window.character==entry.name
            local texture=portraitImage(entry.portrait)
            self:drawRect(x,top,size,size,.96,.76,.76,.79)
            if texture then self:drawTextureScaledAspect(texture,x,top,size,size,1,1,1,1) end
            self:drawRect(x,top+size,size,height-size,.97,.10,.14,.16)
            label(self,entry.name,x+size/2,top+size+1,17,size-10,.94,.94,.93,1,true)
            if entry.locked or entry.unlockRequired then
                self:drawRect(x,top,size,size,.49,.05,.06,.06)
                lock(self,x+size-24,top+size-24)
            end
            if selected then
                self:drawRectBorder(x,top,size,height,1,.99,.55,.21)
                self:drawRect(x+1,top+height-2,size-2,2,1,.99,.55,.21)
            elseif hover>0 then
                self:drawRectBorder(x,top,size,height,hover*.85,.69,.82,.83)
            end
        end
    else
        local entry=item.item
        local selected=window.character==entry.name
        local t=portraitImage(entry.portrait)
        local shade=(entry.locked or entry.unlockRequired) and .47 or 1
        local cx,cy=55,y+item.height/2
        local hover=hoverAmount(entry,self.roomHoverCharacter==entry)
        local size=selected and 84 or 76+2*hover
        if t then self:drawTextureScaled(t,cx-size/2,cy-size/2,size,size,1,shade,shade,shade) end
        local ring=image(data.portraitRing)
        if ring then
            if selected then
                self:drawTextureScaled(ring,cx-46,cy-46,92,92,.99,1,.71,.38)
            else self:drawTextureScaled(ring,cx-size/2-2,cy-size/2-2,size+4,size+4,
                .68+.22*hover,.37+.28*hover,.42+.34*hover,.43+.34*hover) end
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
        hideTooltip(widget)
        self.content:removeChild(widget)
    end
    self.widgets={};self.carouselPanel=nil
    if self.content.roomScrollDrag then self.content:onMouseUp() end
    self.content:setYScroll(0)
end
function W:addLabel(value,y,textHeight,width)
    textHeight=textHeight or 20
    local p=ISPanel:new(0,y,width or self.rightWidth-32,math.max(25,textHeight+6));p:initialise();p.background=false
    p.prerender=function(s)label(s,value,0,3,textHeight,s.width-4,.83,.90,.89)end
    self.content:addChild(p);table.insert(self.widgets,p)
end
function W:addTile(x,y,width,height,title,path,active,callback)
    local b=ISButton:new(x,y,width,height,"",self,callback);b:initialise()
    b.roomTitle=title;b.roomActive=active
    b:setSound("activate","GFL2RoomSelect")
    b:setTooltip(title)
    local captionHeight=math.max(25,descriptionHeight()+6)
    b.prerender=function(s)
        local enabled=s.enable~=false
        tile(s,0,0,s.width,s.height,path,title,captionHeight,active,hoverAmount(s,enabled and (s.mouseOver or s.joypadFocused)),enabled and s.pressed)
        s:updateTooltip()
    end
    b.render=function()end
    b:setEnable(not self.waiting);self.content:addChild(b);table.insert(self.widgets,b);return b
end
function W:carousel(character)
    local p=ISPanel:new(0,0,self.content.width-16,self.content.height);p:initialise();p.background=false
    p.entries=character.skins;p.index=1;p.offset=0
    local function cardHeight(s)return math.min(math.floor(self.fullHeight*.44),s.height-28) end
    for i,e in ipairs(p.entries)do if e.id==(self.browseId or self.currentId)then p.index=i end end
    local function step(s)return cardHeight(s)*.505+15 end
    local function position(s)
        if s.dragging then return clamp(s.dragOrigin-s.offset/step(s),1,#s.entries) end
        if not s.changedAt then return s.index end
        local t=ease((getTimestampMs()-s.changedAt)/180)
        return s.slideFrom+(s.index-s.slideFrom)*t
    end
    local function select(s,index,from)
        index=clamp(index,1,#s.entries)
        from=from or position(s)
        if index~=s.index then roomSound("Select") end
        s.index=index;s.slideFrom=from;s.changedAt=getTimestampMs();s.offset=0
        self:previewEntry(s.entries[index]);self:updateWear()
    end
    local function cardRect(s,i,cursor)
        local height=cardHeight(s)
        local width=height*.505
        local scale=.88+.12*math.max(0,1-math.abs(i-cursor))
        local w,h=width*scale,height*scale
        return (s.width-w)/2+(i-cursor)*step(s),(s.height-h)/2,w,h
    end
    local function cardAt(s,x,y,phase)
        if x<0 or x>=s.width or y<0 or y>=s.height then return nil end
        for i=1,#s.entries do
            local left,top,w,h=cardRect(s,i,phase)
            if x>=left and x<left+w and y>=top and y<top+h then return i end
        end
    end
    p.prerender=function(s)
        s:setStencilRect(0,0,s.width,s.height)
        local phase=position(s)
        local hover=s:isMouseOver() and not s.dragging and cardAt(s,s:getMouseX(),s:getMouseY(),phase)
        local function card(i)
            local active=i==s.index
            local x,y,w,h=cardRect(s,i,phase)
            if x+w<=0 or x>=s.width then return end
            local meta=s.entries[i].meta
            local amount=hoverAmount(s.entries[i],hover==i)
            local t=image(meta.card)
            if t then s:drawTextureScaledAspect(t,x,y,w,h,(active and 1 or .61+.24*amount)*(s.roomEnterAlpha or 1),1,1,1)end
            surface(s,x+3,y+h-38,w-6,34,.62)
            label(s,outfitName(s.entries[i]),x+w/2,y+h-31,17,w-14,.96,.96,.94,1,true)
            if s.entries[i].outfitLocked and s.entries[i].id~=self.currentId then
                s:drawRect(x,y,w,h,.43,.02,.035,.045);lock(s,x+w/2-10,y+h/2-10)
            end
            if active then
                s:drawRectBorder(x,y,w,h,1,.99,.55,.21)
                s:drawRect(x+1,y+h-2,w-2,2,1,.99,.55,.21)
            elseif amount>0 then
                s:drawRectBorder(x,y,w,h,amount*.88,.69,.82,.83)
            end
        end
        for i=1,#s.entries do if i~=s.index then card(i)end end
        card(s.index)
    end
    p.render=function(s)s:clearStencilRect()end
    p.onMouseDown=function(s,x,y)
        s.dragOrigin=position(s)
        s.pressedCard=cardAt(s,x,y,s.dragOrigin)
        s.dragging=true;s.offset=0;s:setCapture(true)
        return true
    end
    p.onMouseMove=function(s,dx,dy)if s.dragging then s.offset=s.offset+dx end;return true end
    p.onMouseMoveOutside=p.onMouseMove
    p.onMouseUp=function(s,x,y)
        if not s.dragging then return true end
        local cursor=position(s)
        local target=s.index
        if math.abs(s.offset)>24 then
            target=s.dragOrigin+(s.offset<0 and 1 or -1)*math.max(1,math.floor(math.abs(s.offset)/step(s)+.5))
            target=math.floor(target+.5)
        else
            local clicked=x and y and cardAt(s,x,y,cursor)
            if clicked and clicked==s.pressedCard then target=clicked end
        end
        s.dragging=false;s:setCapture(false);s.pressedCard=nil
        select(s,target,cursor)
        return true
    end
    p.onMouseUpOutside=p.onMouseUp
    p.onMouseWheel=function(s,delta)
        if delta~=0 and not s.dragging then select(s,s.index+(delta>0 and 1 or -1)) end
        return true
    end
    self.content:addChild(p);table.insert(self.widgets,p);self.carouselPanel=p;select(p,p.index)
end

function W:canEditCurrent()
    local character=self.byCharacter and self.byCharacter[self.character]
    return self.browsed and self.browsed.id==self.currentId and self.selected and not self.waiting
        and not (character and character.unlockRequired) and GFL2CharacterRuntime.isEnabled(self.player)
end

function W:toolMessage()
    return tr(self.waiting and "Loading" or "RoomActivateBeforeTools")
end

function W:requestMotionPage()
    if not motionEntryVisible(self) then return end
    if self:canEditCurrent() then
        self.toolNotice=nil
        self:setRoomPage("motions")
    else self.toolNotice=self:toolMessage() end
end

function W:toggleBodyPhysics()
    if not self:canEditCurrent() then return end
    if self.actions.bodyPhysics(self.player,not GFL2CharacterRuntime.modelPhysicsEnabled(self.player)) then
        self:layoutOverview()
    end
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
    local character=self.byCharacter[self.character]
    self.skinButton:setEnable(skins or (self.browsed~=nil and not (character and character.unlockRequired) and not self.waiting))
    self.wearButton.roomLook=skins and locked and self.browsed.price and "outfitPurchase" or "primary"
    self.wearButton.roomTitle=tr(skins and (wearing and "Wearing" or locked and self.browsed.outfitPool and "RoomOutfitProcurement"
        or locked and "Buy" or "Wear")
        or (wearing and "CharacterEnabled" or "EnableCharacter"))
    local pendingUnlock=character and character.unlockRequired
    self.wearButton:setEnable(self.browsed~=nil and not wearing and (not locked or skins) and not pendingUnlock and not self.waiting)
    local available=self.browsed~=nil and not locked and not pendingUnlock
    for _,button in ipairs({self.pageButton,self.physicsResetButton,
        self.gripButton,self.expressionButton,self.motionTab}) do button:setEnable(available) end
end
function W:previewEntry(entry)
    local id=entry and entry.id
    if id~=self.browseId then
        self.toolNotice=nil
        if not entry or not self.browsed or entry.meta.character~=self.browsed.meta.character then
            self.previewRequestId=nil
            GFL2ReadyRoom.release(self.player)
        end
        self.previewAt=getTimestampMs()+(self.previewRequestId and 65 or 0)
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
    self:roomTransaction("BuyOutfit",entry.id,nil,function()
        return economy.buyOutfit(self.player,entry.id,entry.price)
    end,function(status)
        if status=="short" then self:openExchange("credits");return end
        if status=="unlocked" then
            self:refresh()
            self.toolNotice=tr("RoomOutfitAcquired")
        end
    end)
end
function W:refresh()
    self:closeWorkshopPrompt()
    self.currentId=GFL2CharacterRuntime.modelId(self.player);self.currentLabel=tr("Choose");self.selected=nil
    self.characters={};self.byCharacter={}
    local equippedCharacter
    local defaultSkins=data.roomDefaultSkinNames or {}
    local prices=data.roomOutfitPrices or {}
    local starters=data.roomStarterSkins or {}
    local optional=data.optionalModels or {}
    local optionalIds={};local available={}
    for _,id in ipairs(optional)do optionalIds[id]=true end
    local function add(id,index,meta)
        local character=self.byCharacter[meta.character]
        if not character then
            local card=data.roomRecruitment[meta.character] or (meta.pack and {pool=meta.pack})
            local locked=card~=nil and not economy.characterUnlocked(self.player,card.pool)
            if self.drawAnimation and self.drawAnimation.character==meta.character then locked=true end
            character={name=meta.character,portrait=meta.portrait,skins={},locked=true,unlockRequired=not not locked,recruitment=card}
            self.byCharacter[meta.character]=character;table.insert(self.characters,character)
        end
        if index==nil then return end
        local entry={id=id,index=index,meta=meta,outfitPool=(data.roomOutfitPools or {})[id]}
        character.locked=false;table.insert(character.skins,entry)
        local original=meta.isDefault==true or not not defaultSkins[meta.skin]
        entry.isDefault=original
        local starter=starters[meta.character]
        entry.price=not original and not meta.included and id~=starter and not entry.outfitPool and (prices[id] or economy.defaultOutfitPrice) or nil
        entry.sortOrder=original and 0 or entry.outfitPool and 2 or 1
        if not entry.outfitPool and (id==starter or not character.baseSkin or (original and not character.baseSkin.isDefault)) then
            character.baseSkin=entry
        end
        if id==self.currentId then equippedCharacter=meta.character;self.currentLabel=outfitName(entry);self.selected=entry end
    end
    for index=0,GFL2CharacterRuntime.modelCount()-1 do
        local id=GFL2CharacterRuntime.modelIdAt(index);local meta=data.models[id]
        if not meta and not data.excluded[id] then
            local name,skin=GFL2CharacterRuntime.modelLabel(index):match("^(.-) / (.+)$")
            meta={character=name or GFL2CharacterRuntime.modelLabel(index),skin=data.skinNames[skin] or skin or tr("Default")}
        end
        if GFL2CharacterRuntime.modelListed(index) and meta then
            if optionalIds[id] then available[id]=index else add(id,index,meta) end
        end
    end
    for _,id in ipairs(optional)do add(id,available[id],data.models[id])end
    for _,character in ipairs(self.characters)do
        local base=character.baseSkin
        table.sort(character.skins,function(a,b)
            if (a==base)~=(b==base) then return a==base end
            if a.sortOrder~=b.sortOrder then return a.sortOrder<b.sortOrder end
            return a.index<b.index
        end)
        if base then character.portrait=base.meta.portrait or character.portrait end
        for _,skin in ipairs(character.skins)do
            skin.outfitLocked=skin~=base and not skin.meta.included and not economy.outfitOwned(self.player,skin.id)
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
    local changed=self.tab~=tab
    local returning=tab=="overview" or (tab=="skins" and (self.tab=="parts" or self.tab=="outfitDraw"))
    if self.tab~=tab then
        self:closeWorkshopPrompt()
        if self.tab=="grip" then
            self.preview:onMouseUp()
            GFL2ReadyRoom.release(self.player)
        end
        if tab=="grip" then
            self.gripAiming=false
            self.gripYaw=math.rad(-30)
            self.gripPitch=0
            self.gripZoom=1.55
        end
    end
    if tab=="overview" and self.tab=="skins" and self.browsed and self.browsed.outfitLocked
        and self.browsed.id~=self.currentId then
        local character=self.byCharacter[self.character]
        if character then self:previewEntry(character.baseSkin or character.skins[1]) end
    end
    if self.tab~=tab then
        self.expressionSaving=false
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
    if changed then self:beginTabMotion(returning and -1 or 1) end
end

function W:endTabMotion()
    if not self.tabMotion then return end
    for widget,y in pairs(self.tabMotion.rows)do widget:setY(y);widget.roomEnterAlpha=nil end
    self.tabMotion=nil
end

function W:beginTabMotion(direction)
    self:endTabMotion()
    local rows={}
    for _,widget in ipairs(self.widgets)do rows[widget]=widget.y end
    if self.tab=="overview" then
        for _,widget in ipairs({self.gripButton,self.expressionButton,self.bodyPhysicsButton,self.physicsResetButton,self.motionTab,self.restoreButton})do
            if widget:getIsVisible() then rows[widget]=widget.y end
        end
    end
    self.tabMotion={rows=rows,start=getTimestampMs(),direction=direction}
    self:updateTabMotion()
end

function W:updateTabMotion()
    local motion=self.tabMotion
    if not motion then return end
    local phase=ease((getTimestampMs()-motion.start)/150)
    if phase==1 then self:endTabMotion();return end
    for widget,y in pairs(motion.rows)do
        widget:setY(y+math.floor(motion.direction*6*(1-phase)+.5))
        widget.roomEnterAlpha=.65+.35*phase
    end
end
function W:partRows(extrasOnly)
            local names=self.selected and self.selected.meta.primaryCategories or {data.text.Head,data.text.Top,data.text.Bottom}
            local primary={};for _,name in ipairs(names)do primary[name]=true end
            local groups=GFL2CharacterRuntime.groupCount(self.player);local categories={};local extra={}
            for group=0,groups-1 do
                local name=GFL2CharacterRuntime.groupLabel(self.player,group)
                local normalized=data.categories[name] or name
                local blink=false
                for option=0,GFL2CharacterRuntime.optionCount(self.player,group)-1 do
                    local optionLabel=GFL2CharacterRuntime.optionLabel(self.player,group,option)
                    if optionLabel==data.text.EyeOpen or optionLabel==data.text.EyeClosed then blink=true;break end
                end
                if not blink then
                    local row={index=group,label=normalized}
                    if primary[normalized] then categories[normalized]=row else table.insert(extra,row) end
                else
                    primary[normalized]=nil
                end
            end
            local ordered={}
            if extrasOnly then ordered=extra else
                for _,name in ipairs(names)do
                    if primary[name] then table.insert(ordered,categories[name] or {label=name}) end
                end
            end
            return ordered
end
local function localizedLabel(kind,value)
    return (getTextOrNull and getTextOrNull("UI_GFL2_"..kind.."_"..value)) or value
end
function W:drawGroups(ordered,y,width)
    local step=descriptionHeight()
    local textHeight=math.max(20,step-2)
    local tileHeight=math.max(77,step+48)
    local rowStep=tileHeight+8
    for _,row in ipairs(ordered) do
        self:addLabel(localizedLabel("PartGroup",row.label),y,textHeight)
        y=y+textHeight+10
        if row.index then
            local group=row.index
            local count=GFL2CharacterRuntime.optionCount(self.player,group)
            local selectedOption=GFL2CharacterRuntime.selectedOption(self.player,group)
            local pictures=self.selected.meta.partImages
            local groupPictures=pictures and pictures[GFL2CharacterRuntime.groupLabel(self.player,group)]
            for option=0,count-1 do
                local cardWidth=(width-16)/3
                local x=(option%3)*(cardWidth+8)
                local yy=y+math.floor(option/3)*rowStep
                local optionLabel=GFL2CharacterRuntime.optionLabel(self.player,group,option)
                self:addTile(x,yy,cardWidth,tileHeight,localizedLabel("PartOption",optionLabel),
                    groupPictures and groupPictures[optionLabel],selectedOption==option,function(s)
                        if s.actions.choice(s.player,group,option) then s:refreshControls(true) end
                    end)
            end
            y=y+math.ceil(count/3)*rowStep+10
        else
            local p=ISPanel:new(0,y,width,textHeight+16);p:initialise();p.background=false
            p.prerender=function(s)label(s,tr("NoCategory"),5,6,17,s.width-10,.56,.66,.69)end
            self.content:addChild(p);table.insert(self.widgets,p);y=y+textHeight+26
        end
    end
    return y
end
function W:refreshControls(keepScroll)
    self:endTabMotion()
    if self.folded or self.foldStart then return end
    if self.roomPage=="motions" then self:refreshMotionControls();return end
    local editable=self:canEditCurrent()
    self:layoutPreview()
    self.gripAimButton:setVisible(false)
    self.preview:setVisible(self.tab~="weaponDraw")
    local scroll=keepScroll and self.content:getYScroll() or 0
    self:clearContent()
    local enabled=GFL2CharacterRuntime.isEnabled(self.player)
    self.restoreButton:setEnable(enabled)
    self.content:setX(self.tab=="skins" and self.overviewX-57 or self.rightX)
    self.content:setWidth(self.tab=="skins" and self.overviewWidth+64 or self.rightWidth)
    self.content:setY(self.tab=="skins" and math.floor(self.height*.19) or 91)
    self.content:setHeight(self.tab=="skins" and math.floor(self.height*.57) or self.height-self.content.y-112)
    local y=8;local width=self.rightWidth-32;local showExpressionSlots=false
    if self.tab=="overview" then
        y=0
    elseif self.tab=="outfitDraw" or self.tab=="weaponDraw" then
        y=0
    elseif self.tab=="skins" then
        local character=self.byCharacter[self.character]
        if character and not character.locked then self:carousel(character) end
        y=self.content.height
    elseif not editable then
        self:addLabel(self:toolMessage(),8);y=44
    else
        if self.tab=="expression" then
            local id=self.currentId
            local rows=GFL2ReadyRoom.expressionRows(self.player,id)
            y=self:drawExpressionPresets(rows,y,width)
            showExpressionSlots=#rows>0
            if showExpressionSlots then
                self.content:setHeight(self.fullHeight-116-self.content.y-6)
                if GFL2ReadyRoom.motionState(self.player).name~="" then
                    self:addLabel(tr("ExpressionVmd"),y);y=y+28
                end
                local group
                for _,row in ipairs(rows)do
                    if group~=row.group then group=row.group;self:addLabel(tr(group),y);y=y+31 end
                    local field=row.index
                    self:addLabel(localizedLabel("ExpressionControl",row.label),y,18,width-60)
                    local slider=ISSliderPanel:new(5,y+24,width-10,20,self,function(w,value)
                        local error=GFL2ReadyRoom.expressionSet(w.player,id,field,value/100)
                        if error~="" then print("[GFL2Exilium] "..error);w.status=tr("Error")
                        else w.actions.expressionEdited(w.player) end
                    end)
                    slider:initialise();slider:instantiate();slider:setDoButtons(false);slider:setValues(0,100,1,10,true)
                    slider:setCurrentValue(row.value*100,true)
                    local render=slider.render
                    slider.render=function(s)
                        render(s)
                        textInSlot(s,string.format("%d%%",math.floor(s.currentValue+.5)),s.width,-21,18,50,.81,.88,.92,1,1)
                    end
                    slider.roomSlider=true;self.content:addChild(slider);table.insert(self.widgets,slider);y=y+49
                end
            else self:addLabel(tr("ExpressionEmpty"),y);y=y+30 end
        elseif self.tab=="grip" then
            local id=self.currentId
            local family=GFL2ReadyRoom.gripFamily(self.player)
            self.gripFamily=family
            if family=="" then
                self:addLabel(getText("ContextMenu_GFL2_GripEquipItem"),y);y=y+32
            else
                local canAim=GFL2ReadyRoom.canAimGrip(self.player)
                if not canAim then self.gripAiming=false end
                local aiming=self.gripAiming==true
                if canAim then
                    local mode=aiming and "ContextMenu_GFL2_GripStanding" or "ContextMenu_GFL2_GripAim"
                    self.gripAimButton.roomTitle=getText(mode)
                    self.gripAimButton.roomActive=aiming
                    self.gripAimButton:setVisible(true)
                end
                self:addLabel(GFL2ReadyRoom.gripItemName(self.player),y);y=y+32
                local values=GFL2ReadyRoom.gripValues(self.player,family,aiming)
                local groups={
                    {getText("ContextMenu_GFL2_GripItemPosition"),12,20,{"X","Y","Z"},""},
                    {tr("GripSupport"),0,12,{"X","Y","Z"},""},
                    {tr("GripSupportWrist"),6,90,{"X","Y","Z"},"°"},
                    {tr("GripPrimary"),3,12,{"X","Y","Z"},""},
                    {tr("GripPrimaryWrist"),9,90,{"X","Y","Z"},"°"},
                    {getText("ContextMenu_GFL2_GripCurl"),15,100,
                        {getText("ContextMenu_GFL2_GripLeftHand"),getText("ContextMenu_GFL2_GripRightHand")},"%"},
                }
                local resetWidth=64
                for _,group in ipairs(groups)do
                    local title,start,limit,axes,unit=unpack(group)
                    self:addLabel(title,y);y=y+28
                    local inset=unit=="%" and 76 or 34
                    for i,axis in ipairs(axes)do
                        local field=start+i-1
                        self:addLabel(axis,y,17)
                        local slider=ISSliderPanel:new(inset,y+2,width-inset-resetWidth-8,20,self,function(w,value)
                            if not GFL2ReadyRoom.gripSet(w.player,id,family,field,value,aiming)then w.status=tr("Error") end
                        end)
                        slider:initialise();slider:instantiate();slider:setDoButtons(true);slider:setValues(-limit,limit,.1,1,true)
                        slider:setCurrentValue(values[field+1] or 0,true)
                        local render=slider.render
                        slider.render=function(s)
                            render(s)
                            textInSlot(s,string.format("%+.1f",s.currentValue)..unit,s.width,20,18,76,.81,.88,.92,1,1)
                        end
                        slider.roomSlider=true;self.content:addChild(slider);table.insert(self.widgets,slider)
                        local reset=button(self.content,width-resetWidth,y,resetWidth,getText("ContextMenu_GFL2_GripResetValue"),self,function(w)
                            if GFL2ReadyRoom.gripSet(w.player,id,family,field,0,aiming) then
                                slider:setCurrentValue(0,true)
                            else w.status=tr("Error") end
                        end)
                        table.insert(self.widgets,reset);y=y+46
                    end
                end
                local b=button(self.content,0,y+4,width,tr("Reset"),self,function(w)
                    if GFL2ReadyRoom.gripSet(w.player,id,family,-1,0,aiming)then w:refreshControls(true) end
                end)
                table.insert(self.widgets,b);y=y+42
            end
        elseif self.tab=="parts" then
            y=self:drawGroups(self:partRows(false),y,width)
            y=self:drawGroups(self:partRows(true),y,width)
            for row=0,GFL2CharacterRuntime.materialCount(self.player)-1 do
                local index=GFL2CharacterRuntime.materialIndex(self.player,row);local available=GFL2CharacterRuntime.materialAvailable(self.player,index)
                local b=button(self.content,0,y,width,GFL2CharacterRuntime.materialLabel(self.player,index),self,function(s)
                    if s.actions.material(s.player,index,not GFL2CharacterRuntime.materialVisible(s.player,index))then s:refreshControls(true)end
                end)
                b:setEnable(available);b.roomActive=GFL2CharacterRuntime.materialVisible(self.player,index);table.insert(self.widgets,b);y=y+34
            end
            if GFL2CharacterRuntime.presetCount(self.player)>0 then
                self:addLabel(tr("Presets"),y);y=y+30
                for index=0,GFL2CharacterRuntime.presetCount(self.player)-1 do
                    local b=button(self.content,0,y,width,GFL2CharacterRuntime.presetLabel(self.player,index),self,function(s)if s.actions.preset(s.player,index)then s:refreshControls(true)end end)
                    table.insert(self.widgets,b);y=y+34
                end
            end
            local half=math.floor((width-8)/2)
            local reset=button(self.content,0,y,half,tr("Reset"),self,self.onReset)
            local reload=button(self.content,half+8,y,half,tr("Reload"),self,self.onReload)
            table.insert(self.widgets,reset);table.insert(self.widgets,reload);y=y+38
        end
    end
    if self.tab=="grip" or self.tab=="expression" or self.tab=="parts" then
        for _,widget in ipairs(self.widgets) do widget:setX(widget.x+12) end
        y=y+8
        self.content:setHeight(math.min(self.content.height,y))
    end
    if showExpressionSlots then self.expressionFooter:setY(self.content.y+self.content.height+6) end
    self:refreshExpressionSlots(showExpressionSlots)
    self:updateWear()
    self.content:setScrollHeight(math.max(y,self.content.height))
    self.content:setYScroll(clamp(scroll,self.content.height-self.content:getScrollHeight(),0))
    self.status=self.waiting and tr("Loading") or enabled and tr("Saved") or tr("Original")
    self:layoutOverview()
    self.content:setVisible(self.tab~="overview" and self.tab~="outfitDraw" and (self.tab~="weaponDraw" or self.signatureWeapon~=nil))
end
function W:selectSkin(entry)
    local character=self.byCharacter[self.character]
    if not character or character.unlockRequired or self.waiting or entry.outfitLocked or self.currentId==entry.id then return false end
    local function equip()
        if self.actions.enable(self.player,entry.index) then
            self.toolNotice=nil;self.waiting=true;self.yaw=0;self:refresh()
            return true
        end
        self.status=tr("Error")
        return false
    end
    if not self.multiplayerSession then return equip() end
    if not multiplayer.ready(self.player) or multiplayer.pending(self.player) then return false end
    return multiplayer.transact(self.player,"Wear",entry.id,nil,function(result)
        if self.closed then return end
        if result.status=="ok" then equip() else self.status=tr("Error") end
    end)
end

function W:onCharacter(character)
    if self.waiting or self.drawAnimation or not character or (self.multiplayerSession and multiplayer.pending(self.player)) then return end
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
        for i=0,GFL2CharacterRuntime.groupCount(self.player)-1 do
            if GFL2CharacterRuntime.groupLabel(self.player,i)==eyes.label then group=i;break end
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
                    if GFL2ReadyRoom.expressionSet(w.player,w.currentId,blink,option)~="" then return end
                    w.actions.expressionRemember(w.player)
                end
                w:refreshControls(true)
            end)
            b.roomActive=group~=nil and GFL2CharacterRuntime.selectedOption(self.player,group)==option
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
                local error=GFL2ReadyRoom.expressionLoad(w.player,w.currentId,payload)
                if error~="" then print("[GFL2Exilium] "..error);return end
                w.actions.expressionRemember(w.player)
                w:refreshControls(true)
            end)
            table.insert(self.widgets,b)
        end
    end
    return y+38
end

function W:onRestore()self.actions.disable(self.player);if GFL2ReadyRoom then GFL2ReadyRoom.release(self.player)end;self.waiting=false;self.selected=nil;self:refresh()end
function W:onReset()if self.actions.reset(self.player)then self:refreshControls()end end
function W:onReload()if self.actions.reload(self.player)then self:refreshControls()end end
function W:update()
    ISCollapsableWindow.update(self)
    self:updateTabMotion()
    if self.multiplayerSession then
        local ready=not not multiplayer.ready(self.player)
        if self.multiplayerReady~=ready then
            self.multiplayerReady=ready
            if ready and not self.waiting then self:refresh() end
        end
    end
    self:updateDrawAnimation()
    if self.multiplayerSession and self.magazineClaimButton:getIsVisible() then
        self.magazineClaimButton:setEnable(not multiplayer.pending(self.player))
    end
    if self.weaponClaimButton:getIsVisible() and self.signatureWeapon then
        local now=getTimestampMs()
        if now>=(self.nextWeaponInventoryCheck or 0) then
            self.nextWeaponInventoryCheck=now+500
            local carried=economy.hasWeaponInInventory(self.player,self.signatureWeapon.id)
            if self.weaponClaimButton.enable==carried then self:layoutOverview() end
        end
    end
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
        self.rosterToggle:setX(self.sidebar.x+self.sidebar.width)
        if phase==1 then self.railStart=nil end
    end
    if self.foldStart then
        local t=clamp((getTimestampMs()-self.foldStart)/160,0,1)
        self:setHeight(math.floor(self.foldFrom+(self.foldTo-self.foldFrom)*ease(t)+.5))
        if t==1 then
            self.foldStart=nil;self:roomVisibility()
            if not self.folded then self:refreshControls() end
        end
    end
    if self.waiting then
        if GFL2CharacterRuntime.hasModelFailure and GFL2CharacterRuntime.hasModelFailure(self.player)then self.waiting=false;self:refresh();self.status=tr("Error")
        elseif GFL2CharacterRuntime.isReady(self.player)then self.waiting=false;self:refresh()end
    elseif GFL2CharacterRuntime.modelId(self.player)~=self.currentId then
        self:refresh()
    end
    if not self.folded and not self.foldStart and self.roomPage=="motions" then self:refreshMotionControls() end
    if not self.folded and not self.foldStart and self.roomPage=="appearance" and self.tab=="grip"
        and self:canEditCurrent() and GFL2ReadyRoom.gripFamily(self.player)~=(self.gripFamily or "") then
        self.gripAiming=false
        self:refreshControls(true)
    end
end
function W:layoutBiography(character)
    local panel=self.biographyPanel
    local bottom=self.weaponCard:getIsVisible() and self.weaponCard.y or self.wearButton.y
    local available=bottom-panel.y-10
    local show=character and not character.locked and not character.unlockRequired
        and self.tab=="overview" and not self.toolNotice and not self.folded and not self.foldStart
        and self.roomPage~="motions" and available>18
    local source=show and (biographies[character.name] or (data.roomCharacterDescriptions or {})[character.name])
    if not source then
        if panel.roomScrollDrag then panel:onMouseUp() end
        if panel:getIsVisible() then panel:setVisible(false) end
        panel.lines=nil
        return
    end
    local step=descriptionHeight()
    local textWidth=panel.width-28
    local lines=describe(source,textWidth)
    local contentHeight=18+#lines*step
    if contentHeight>available then
        textWidth=textWidth-14
        lines=describe(source,textWidth)
        contentHeight=18+#lines*step
    end
    local height=math.min(available,contentHeight)
    local resized=panel.height~=height
    if resized then panel:setHeight(height) end
    if panel.lines~=lines or panel.contentHeight~=contentHeight or resized then
        panel:setYScroll(0)
        panel.lines,panel.textWidth,panel.contentHeight=lines,textWidth,contentHeight
        panel:setScrollHeight(contentHeight)
    end
    if not panel:getIsVisible() then panel:setVisible(true) end
end
function W:prerender()
    local header=self:titleBarHeight()
    self:layoutBiography(self.byCharacter[self.character])
    if self.folded or self.foldStart then
        surface(self,0,0,self.width,math.min(header,self.height),.68)
        local title=self.roomPage=="motions" and tr("RoomMotion") or self.title
        label(self,title,76,14,23,self.width-120)
        return
    end
    if self.roomPage=="motions" then
        surface(self,0,0,self.width,header,.87)
        label(self,tr("RoomMotion"),76,14,23,self.width-120)
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
    local titles={skins="RoomOutfits",outfitDraw="RoomOutfitProcurement",parts="RoomPartSwitch",grip="GripCalibration",expression="Expression"}
    local page=titles[self.tab]
    if self.tab=="weaponDraw" then label(self,getText("ContextMenu_GFL2_WeaponPool"),76,14,23,self.width-180)
    elseif page then label(self,tr(page),76,14,23,self.width-180)
    else label(self,self.title,76,14,23,self.width-180) end
    local character=self.byCharacter[self.character]
    local signatureWeapon=self.signatureWeapon
    if self.tab=="overview" then
        local rail=self.sidebar
        surface(self,rail.x,rail.y,rail.width,rail.height,.56)
    end
    if self.tab=="weaponDraw" and signatureWeapon then
        local progress=economy.weaponProgress(self.player,signatureWeapon.id)
        if not progress.acquired or self.drawAnimation then
            local misses=self.drawAnimation and self.drawAnimation.misses or economy.weaponMisses(self.player)
            self:drawElitePity(economy.weaponGuarantee-misses)
        elseif self.drawNotice then
            local left=self.drawButtons[1].x
            label(self,self.drawNotice,left,self.weaponClaimButton.y-32,16,self.weaponClaimButton.x+self.weaponClaimButton.width-left)
        end
    elseif (self.tab=="skins" or self.tab=="outfitDraw") and self.browsed then
        local x=math.floor(self.width*.045)
        local inPool=self.tab=="outfitDraw"
        local coverWidth=math.floor(self.width*.245)
        local width=coverWidth
        local meta=self.browsed.meta
        local pool=self.browsed.outfitPool
        local cover=inPool and pool and image(pool.banner)
        local coverHeight=cover and math.floor(coverWidth*cover:getHeight()/cover:getWidth()) or 0
        local name=outfitName(self.browsed)
        local nameArt=(data.roomLabels and data.roomLabels[name]) or data.labels[name]
        local hasNameArt=nameArt and image(nameArt.path)
        local descriptions=data.skinDescriptions or {}
        local source=outfitDescriptions[self.browsed.id] or descriptions[self.browsed.id] or descriptions[meta.card or ""]
        local step=descriptionHeight()
        local roleHeight=math.max(24,step+4)
        local heading,headingHeight,text,bodyTop,y
        for attempt=1,2 do
            heading=not hasNameArt and describe(name,width) or nil
            headingHeight=math.max(40,#(heading or {})*step+8)
            text=describe(source,width)
            bodyTop=headingHeight+roleHeight+16
            y=math.min(math.floor(self.height*(inPool and .605 or .668)),self.height-42-bodyTop-#(text or {})*step)
            if attempt==2 or y>=header+(cover and coverHeight+14 or 0) then break end
            width=math.max(width,self.rightX-x-24)
        end
        surface(self,x-12,y-8,width+24,bodyTop+#(text or {})*step+18,.90)
        if cover then self:drawTextureScaled(cover,x,y-coverHeight-14,coverWidth,coverHeight,1,1,1,1) end
        if heading then
            for i,line in ipairs(heading) do
                self:drawText(line,x,y+(i-1)*step,.94,.94,.94,1,UIFont.Small)
            end
        else
            label(self,name,x,y,32,width,.94,.94,.94)
        end
        label(self,self.character,x,y+headingHeight,19,width,.78,.78,.77)
        self:drawRect(x,y+bodyTop-10,width,1,.25,.66,.76,.76)
        description(self,text,x,y+bodyTop,width)
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
        local bio=self.biographyPanel
        local height=bio.y-y+(bio:getIsVisible() and bio.height or self.toolNotice and 35 or 0)
        frosted(self,self,x,y,width,height,.94)
        label(self,self.character,x+14,y+9,32,width-28,.95,.95,.94)
        self:drawRect(x+14,y+46,width-28,1,.29,.63,.73,.73)
        if bio:getIsVisible() or self.toolNotice then
            self:drawRect(x+14,bio.y,width-28,1,.24,.63,.73,.73)
        end
        if self.toolNotice then
            label(self,self.toolNotice,x+14,bio.y+8,18,width-28)
        end
    end
end
local function elitePityLayout(window,remaining)
    local first,last=window.drawButtons[1],window.drawButtons[2]
    local right=last.x+last.width
    local count=tostring(remaining)
    local copy=getText("ContextMenu_GFL2_PityCounter")
    local tm=getTextManager()
    local font=UIFont.Medium
    if tm:MeasureStringX(font,count..copy)>right-first.x-20 then font=UIFont.Small end
    local countWidth=tm:MeasureStringX(font,count)
    local width=math.max(right-first.x,countWidth+tm:MeasureStringX(font,copy)+20)
    local fontHeight=tm:getFontHeight(font)
    local height=math.max(24,fontHeight+4)
    local y=first.y-35-height
    return right-width,y,width,height,font,fontHeight,count,copy,countWidth
end

function W:drawLockedCharacterInfo(character)
    local card=character.recruitment
    if not card then return end
    local x=math.max(math.floor(self.width*.122),self.rosterToggle.x+self.rosterToggle.width+12)
    local bannerWidth=math.floor(self.width*.164)
    local width=bannerWidth
    local source=biographies[character.name] or (data.roomCharacterDescriptions or {})[character.name]
    local banner=image(card.banner)
    local bannerHeight=banner and math.floor(bannerWidth/2) or 0
    local localized=recruitmentText[character.name]
    local step=descriptionHeight()
    local phaseHeight=localized and localized.element and math.max(28,step+6) or 28
    local nameTop=card.element and 15+phaseHeight or 0
    local tagsTop=nameTop+math.max(38,step+6)
    local baseHeight=card.element and tagsTop+24 or math.max(45,step+6)
    local misses=self.drawAnimation and self.drawAnimation.misses or economy.characterMisses(self.player)
    local remaining=economy.characterGuarantee-misses
    local bottom=self.height-42
    local biography,tags,headerHeight,blockHeight
    for attempt=1,2 do
        biography=describe(source,width)
        tags=localized and describe(localized.tags,width)
        headerHeight=tags and math.max(baseHeight,tagsTop+#tags*step+6) or baseHeight
        blockHeight=bannerHeight+headerHeight+#(biography or {})*step
        if attempt==2 or not (biography and biography.plain)
            or blockHeight<=bottom-self:titleBarHeight() then break end
        width=math.max(width,self.rightX-x-24)
    end
    local headingX,headingY,headingWidth=x,bannerHeight,width
    local bodyY=bannerHeight+headerHeight
    if biography and biography.plain and blockHeight>bottom-self:titleBarHeight() then
        -- Long localized cards can use the otherwise empty area above the recruitment controls.
        local _,pityTop=elitePityLayout(self,remaining)
        if self:showingReward() then pityTop=math.min(pityTop,self.drawButtons[1].y-99) end
        bottom=pityTop-5-(self.drawNotice and math.max(20,step) or 0)
        width=self.width-x-24
        headingX=banner and x+bannerWidth+16 or x
        headingY=0
        headingWidth=x+width-headingX
        tags=localized and describe(localized.tags,headingWidth)
        nameTop=card.element and 8+phaseHeight or 0
        tagsTop=nameTop+step
        headerHeight=tagsTop+#(tags or {})*step
        bodyY=math.max(bannerHeight,headerHeight)+2
        biography=describe(source,width)
        blockHeight=bodyY+#biography*step
    end
    local y=math.min(math.floor(self.height*.65),bottom-blockHeight)
    surface(self,x-10,y-6,width+20,blockHeight+14,.90)
    if banner then
        self:drawTextureScaled(banner,x,y,bannerWidth,bannerHeight,1,1,1,1)
    end
    local headingTop=y+headingY
    if card.element then
        surface(self,headingX,headingTop+8,headingWidth,phaseHeight,.70)
        if localized and localized.element then
            local phase=fitText(localized.element,headingWidth-16,UIFont.Small)
            local top=headingTop+8+(phaseHeight-(step-2))/2
            self:drawText(phase,headingX+8,top,card.color[1],card.color[2],card.color[3],1,UIFont.Small)
        else
            label(self,card.element,headingX+8,headingTop+14,17,headingWidth-16,card.color[1],card.color[2],card.color[3])
        end
    end
    label(self,character.name,headingX,headingTop+nameTop,30,headingWidth,.96,.96,.96)
    if tags then
        for i,line in ipairs(tags) do
            self:drawText(line,headingX,headingTop+tagsTop+(i-1)*step,.89,.92,.92,1,UIFont.Small)
        end
    elseif card.tags and card.tags~="" then
        label(self,card.tags,headingX,headingTop+tagsTop,16,headingWidth,.89,.92,.92)
    end
    description(self,biography,x,y+bodyY,width)
    self:drawElitePity(remaining)
end

function W:drawElitePity(remaining)
    if self:showingReward() then return end
    local x,y,width,height,font,fontHeight,count,copy,countWidth=elitePityLayout(self,remaining)
    local textY=y+(height-fontHeight)/2
    self:drawRect(x,y,width,height,.90,.10,.15,.16)
    self:drawRect(x,y,2,height,.95,.94,.68,.29)
    self:drawText(count,x+10,textY,1,.82,.26,1,font)
    self:drawText(copy,x+10+countWidth,textY,1,.98,.94,1,font)
    if self.drawNotice then label(self,self.drawNotice,x,y-math.max(20,descriptionHeight()),16,width,.96,.87,.73) end
end

function W:drawPity(title,remaining)
    if self:showingReward() then return end
    local button=self.drawButtons[2]
    local right=button.x+button.width
    local top=button.y-56
    number(self,remaining,right,top,16,.94,.95,.95,true)
    label(self,tr(title),right-208,top,16,164,.89,.91,.91)
    if self.drawNotice then label(self,self.drawNotice,self.drawButtons[1].x,top,16,220,.96,.87,.73) end
end

function W:showingReward()
    local a=self.drawAnimation
    return a and a.won and getTimestampMs()>=a.started+(#a.results-1)*drawStep
end
function W:render()
    if self.folded or self.foldStart or self.roomPage=="motions" then return end
    if self.drawAnimation then self:drawRecruitmentResults() end
end
function W:close()
    if self.closed then return end
    self.closed=true
    if self.creditAddButton then self.creditAddButton.creditHold=nil end
    if self.weaponPreview and self.weaponPreview.orbit then self.weaponPreview:onMouseUp() end
    self:closeWorkshopPrompt()
    self:closeExchange()
    if W.instances[self.playerIndex]==self then W.instances[self.playerIndex]=nil end
    self:setVisible(false)
    self:removeFromUIManager()
    for _,child in pairs(self.children) do hideTooltip(child) end
    hideTooltip(self.workshopButton)
    self:refreshExpressionSlots(false)
    if self.motionSeek then self.motionSeek:cancel() end
    if self.motionSpeed then self.motionSpeed.dragInside=false end
    self:closeMotionPathDialog()
    if self.carouselPanel then self.carouselPanel.dragging=false;self.carouselPanel:setCapture(false)end
    self:clearContent();self.preview:onMouseUp();self.sidebar.roomDown=false;self.sidebar:setCapture(false)
    if GFL2ReadyRoom then GFL2ReadyRoom.release(self.player)end
end
function W.open(player,actions)
    if not GFL2ReadyRoom then player:Say(getText("ContextMenu_GFL2Character_JavaUnavailable"));return end
    local index=player:getPlayerNum()
    local window=W.instances[index]
    if window and window.player==player then
        window:bringToTop()
        return window
    end
    if window then window:close() end
    data=loadData()
    loadRoomText()
    local sw,sh=getCore():getScreenWidth(),getCore():getScreenHeight()
    local width=math.min(1510,sw-48);local height=math.min(880,sh-62)
    window=ISCollapsableWindow.new(W,math.floor((sw-width)/2),math.floor((sh-height)/2),width,height)
    window.title=getText("ContextMenu_GFL2Character_Menu")
    window.player=player;window.playerIndex=index;window.actions=actions
    window.multiplayerSession=isClient()
    window.resizable=false;window.drawFrame=false;window.yaw=0;window.zoom=1.03
    window.cameraPan=0;window.cameraFocus=0
    window:initialise();window:addToUIManager();W.instances[index]=window
    window:setVisible(true);window:bringToTop();return window
end
function W.closeAll()
    local windows=W.instances
    W.instances={}
    motionEntryPlayers={}
    for _,window in pairs(windows)do window:close()end
    data=nil;textures={};roomText={};roleNames={};outfitNames={}
    biographies={};outfitDescriptions={};recruitmentText={};descriptionCache={}
end
function W.closeFor(player)
    if not player then return end
    local window=W.instances[player:getPlayerNum()]
    if window and window.player==player then window:close() end
end
return W
