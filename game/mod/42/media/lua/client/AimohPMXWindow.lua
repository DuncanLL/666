require "ISUI/ISCollapsableWindow"
require "ISUI/ISScrollingListBox"
require "ISUI/ISComboBox"
require "ISUI/ISButton"
require "ISUI/ISTextBox"
require "RadioCom/ISUIRadio/ISSliderPanel"
local loadData=require "AimohPMXRoomData"
local data
local textures={}
local function image(path)
    if not path then return nil end
    if not textures[path] then textures[path]=getTexture(path) end
    return textures[path]
end
local art="media/ui/AimohPMXReadyRoom/room-"
local function clamp(value,lo,hi) return math.max(lo,math.min(hi,value)) end
local function ease(t)
    t=1-clamp(t,0,1)
    return 1-t*t*t
end
local function surface(p,x,y,w,h,a)
    if h<=0 or w<=0 then return end
    local texture=image(art.."surface.png")
    if texture then p:drawTextureScaled(texture,x,y,w,h,a,1,1,1)
    else p:drawRect(x,y,w,h,a,.052,.073,.085) end
    p:drawRect(x+1,y,w-2,1,.23*a,.53,.67,.72)
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
local function label(panel,value,x,y,height,width,r,g,b,a)
    local row=data.labels[value];local t=row and image(row.path)
    if t then
        local scale=math.min(height/row.height,(width or row.width)/row.width)
        panel:drawTextureScaled(t,x,y,row.width*scale,row.height*scale,a or 1,r or .91,g or .94,b or .94)
    else panel:drawText(value,x,y,r or .91,g or .94,b or .94,a or 1,UIFont.Small) end
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
function W:titleBarHeight() return 44 end
local function button(parent,x,y,w,title,target,callback)
    local b=ISButton:new(x,y,w,28,"",target,callback);b:initialise();b.roomTitle=title
    b.backgroundColor={r=.09,g=.13,b=.16,a=.91}
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
        local a=enabled and c.a or .44
        if s.roomTab then a=.31+s.roomHover*.38 end
        s:drawRect(0,1,s.width,s.height-1,a,c.r+s.roomHover*.055,c.g+s.roomHover*.065,c.b+s.roomHover*.07)
        local edge=s.borderColor
        if not s.roomTab then
            s:drawRect(1,1,s.width-2,1,(enabled and .37 or .18)*edge.a,edge.r,edge.g,edge.b)
            s:drawRect(1,s.height-1,s.width-2,1,.55,.02,.034,.045)
        end
        if s.roomActive then s:drawRect(0,s.height-2,s.width,2,.8,1,.71,.38) end
    end
    b.render=function(s)
        local enabled=s.enable~=false
        local y=math.floor((s.height-19)/2)+(s.pressed and enabled and 1 or 0)
        local x=8
        local a=enabled and 1 or .53
        if s.roomIcon then
            local icon=image(art..s.roomIcon..".png")
            if icon then s:drawTextureScaled(icon,x,y+3,13,13,a,.91,.94,.96);x=x+17 end
        end
        local width=s.width-x-7
        if s.roomFold then
            width=width-9
            local window=s.parent
            local fold=window.foldStart and clamp((getTimestampMs()-window.foldStart)/160,0,1) or 1
            local folded=window.folded
            if fold<.5 then folded=not folded end
            local h=math.max(1,9*math.abs(1-2*fold))
            local arrow=image(art..(folded and "down" or "up")..".png")
            if arrow then s:drawTextureScaled(arrow,s.width-12,(s.height-h)/2,9,h,a,.90,.77,.59) end
        end
        label(s,s.roomTitle,x,y,19,width,nil,nil,nil,a)
    end
    parent:addChild(b);return b
end
local function panel(parent,x,y,w,h)
    local p=ISPanel:new(x,y,w,h);p:initialise();p.background=false;p:addScrollBars();p:setScrollChildren(true)
    p.onMouseWheel=function(s,delta) s:setYScroll(math.max(math.min(0,s.height-s:getScrollHeight()),math.min(0,s:getYScroll()-delta*48)));return true end
    p.prerender=function(s)
        if s.parent.tab~="skins" then surface(s,0,-s:getYScroll(),s.width,s.height,.88) end
        s:setStencilRect(0,0,s.width,s.height)
    end
    p.render=function(s) s:clearStencilRect() end
    parent:addChild(p);return p
end
function W:createChildren()
    ISCollapsableWindow.createChildren(self)
    self.fullHeight=self.height;self.roomPage="appearance"
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
    local third=(self.rightWidth-12)/3
    self.pageButton=button(self,self.rightX,self.height-94,third,tr("Parts"),self,function(s)s:setTab("parts")end)
    self.chestButton=button(self,self.rightX+third+6,self.height-94,third,tr("ChestPhysics"),self,function(s)s:setTab("chest")end)
    self.gripButton=button(self,self.rightX+(third+6)*2,self.height-94,third,tr("GripCalibration"),self,function(s)s:setTab("grip")end)
    self.skinButton=button(self,self.rightX,self.height-55,132,tr("Back"),self,function(s)s:setTab("skins")end)
    self.wearButton=button(self,self.rightX+140,self.height-55,self.rightWidth-140,tr("Wear"),self,function(w)w:applyBrowse()end)
    self.advancedButton=button(self,self.rightX,105,self.rightWidth,tr("Advanced"),self,function(s)s:setTab(s.tab=="advanced" and "parts" or "advanced")end)
    local physicsX=math.max(308,self.width-386)
    self.physicsResetButton=button(self,physicsX,5,self.width-210-physicsX,tr("PhysicsReset"),self,function(w)w.actions.physicsReset(w.player)end)
    self.physicsResetButton:setHeight(34)
    self.physicsResetButton.backgroundColor={r=.18,g=.125,b=.045,a=.96}
    self.physicsResetButton.backgroundColorMouseOver={r=.32,g=.23,b=.075,a=1}
    self.physicsResetButton.borderColor={r=1,g=.67,b=.24,a=1}
    self.physicsResetButton.roomActive=true
    self.restoreButton=button(self,self.width-198,8,112,tr("Restore"),self,self.onRestore)
    self.resetButton=button(self,self.rightX,64,(self.rightWidth-8)/2,tr("Reset"),self,self.onReset)
    self.reloadButton=button(self,self.rightX+(self.rightWidth+8)/2,64,(self.rightWidth-8)/2,tr("Reload"),self,self.onReload)
    self.eyeOpenButton=button(self,self.rightX,64,(self.rightWidth-8)/2,tr("EyeOpen"),self,function(w)w:onEyes(0)end)
    self.eyeClosedButton=button(self,self.rightX+(self.rightWidth+8)/2,64,(self.rightWidth-8)/2,tr("EyeClosed"),self,function(w)w:onEyes(1)end)
    self.appearanceTab=button(self,134,8,64,tr("RoomAppearance"),self,function(w)w:setRoomPage("appearance")end)
    self.motionTab=button(self,206,8,94,tr("RoomMotion"),self,function(w)w:setRoomPage("motions")end)
    self.foldButton=button(self,self.width-78,8,44,tr("RoomFold"),self,function(w)w:toggleRoomFold()end)
    self.appearanceTab.roomTab=true;self.motionTab.roomTab=true;self.foldButton.roomFold=true
    self:createMotionPage()
    self.widgets={};self.tab="skins";self:roomVisibility();self:refresh()
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
    self.appearanceTab:setVisible(expanded);self.motionTab:setVisible(expanded)
    self.preview:setVisible(expanded and (motions or self.tab~="grip"))
    if motions then
        self.preview:setX(self.motionWidth+28);self.preview:setWidth(self.rightX-self.preview.x-14)
        self.preview:setY(54);self.preview:setHeight(self.fullHeight-68)
    else
        self.preview:setX(math.floor(self.width*.5-390));self.preview:setWidth(780)
        self.preview:setY(50);self.preview:setHeight(self.fullHeight-100)
    end
    for _,widget in ipairs({self.sidebar,self.refreshButton,self.content,self.pageButton,self.chestButton,self.gripButton,
        self.skinButton,self.wearButton,self.advancedButton,self.physicsResetButton,self.restoreButton,
        self.resetButton,self.reloadButton,self.eyeOpenButton,self.eyeClosedButton})do widget:setVisible(appearance)end
    self.motionLibraryBox:setVisible(motions);self.motionBox:setVisible(motions)
    local selected=self.roomPage=="motions" and self.motionTab or self.appearanceTab
    self.tabToX=selected.x+8;self.tabToWidth=selected.width-16
end

function W:setRoomPage(page)
    if self.roomPage==page or self.folded or self.foldStart then return end
    self:closeMotionPathDialog()
    self.motionSeek:cancel()
    self.tabFromX=self.tabDrawX or self.tabToX
    self.tabFromWidth=self.tabDrawWidth or self.tabToWidth
    self.pageFade=getTimestampMs()
    self:clearContent()
    self.preview.dragging=false;self.preview:setCapture(false)
    self.sidebar.roomDown=false;self.sidebar:setCapture(false)
    self.roomPage=page;self:roomVisibility()
    if page=="motions" then self:refreshMotionLibrary() else self:refreshControls() end
end

function W:toggleRoomFold()
    self:closeMotionPathDialog()
    self.motionSeek:cancel()
    self.folded=not self.folded
    self.preview.dragging=false;self.preview:setCapture(false)
    self.sidebar.roomDown=false;self.sidebar:setCapture(false)
    if self.folded then self:clearContent() end
    self.foldFrom=self.height;self.foldStart=getTimestampMs()
    self.foldTo=self.folded and self:titleBarHeight() or self.fullHeight
    self.foldButton.roomTitle=tr(self.folded and "RoomUnfold" or "RoomFold")
    self:roomVisibility()
end
function W:drawCharacter(y,item,alt)
    local entry=item.item;local active=self.parent.character==entry.name
    if active then
        self:drawRect(3,y,88,86,.55,.15,.19,.20)
        self:drawRect(3,y+10,2,62,1,1,.71,.38)
    end
    local t=image(entry.portrait)
    local shade=entry.locked and .5 or 1
    if t then self:drawTextureScaled(t,15,y+6,64,64,1,shade,shade,shade) end
    local ring=image(data.portraitRing)
    if ring then self:drawTextureScaled(ring,12,y+3,70,70,1,active and 1 or .40,active and .62 or .68,active and .24 or .70) end
    if entry.locked then
        self:drawRect(58,y+50,24,25,.95,.08,.10,.12)
        self:drawRectBorder(64,y+54,12,12,1,.91,.79,.52)
        self:drawRect(62,y+61,16,10,1,.91,.79,.52)
        self:drawRect(69,y+64,2,4,1,.08,.10,.12)
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
    self.gripButton:setEnable(editable)
end
function W:applyBrowse()if self.browsed then self:selectSkin(self.browsed)end end
function W:refresh()
    self.currentId=AimohPMXPreview.modelId(self.player);self.currentLabel=tr("Choose")
    self.characters={};self.byCharacter={};self.sidebar:clear()
    local optional=data.optionalModels or {}
    local optionalIds={};local available={}
    for _,id in ipairs(optional)do optionalIds[id]=true end
    local function add(id,index,meta)
        local character=self.byCharacter[meta.character]
        if not character then
            character={name=meta.character,portrait=meta.portrait,skins={},locked=true}
            self.byCharacter[meta.character]=character;table.insert(self.characters,character)
        end
        if index==nil then return end
        local entry={id=id,index=index,meta=meta}
        character.locked=false;table.insert(character.skins,entry)
        if id==self.currentId then self.character=meta.character;self.currentLabel=meta.skin;self.selected=entry end
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
    self.preview:setVisible(self.tab~="grip")
    local scroll=keepScroll and self.content:getYScroll() or 0
    self:clearContent()
    local enabled=AimohPMXPreview.isEnabled(self.player)
    self.physicsResetButton:setEnable(enabled and not self.waiting);self.restoreButton:setEnable(enabled);self.resetButton:setEnable(enabled and not self.waiting);self.reloadButton:setEnable(enabled and not self.waiting)
    local details=self.tab=="parts" or self.tab=="advanced"
    self.content:setY(details and 143 or 105);self.content:setHeight(self.height-self.content.y-142)
    self.skinButton:setVisible(self.tab~="skins")
    self.skinButton:setEnable(not self.waiting)
    for _,item in ipairs({{self.pageButton,details},{self.chestButton,self.tab=="chest"},{self.gripButton,self.tab=="grip"}})do
        item[1].roomActive=item[2]
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
            b.roomActive=active
        end
    end
    self.advancedButton:setVisible(details)
    self.advancedButton.roomTitle=self.tab=="advanced" and tr("BackParts")or tr("Advanced")
    local y=0;local width=self.rightWidth-18
    if self.tab=="skins" then
        local character=self.byCharacter[self.character]
        if character and not character.locked then self:carousel(character) end
        y=self.content.height
    elseif enabled and not self.waiting and self.selected and self.selected.id==self.currentId then
        if self.tab=="grip" then
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
                        self.content:addChild(slider);table.insert(self.widgets,slider);y=y+39
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
        else
            y=self:drawGroups(self:partRows(false),y,width)
        end
    else self:addLabel(self.waiting and tr("Loading") or tr("Choose"),8);y=56 end
    self:updateWear()
    self.content:setScrollHeight(math.max(y,self.content.height))
    self.content:setYScroll(clamp(scroll,self.content.height-self.content:getScrollHeight(),0))
    self.status=self.waiting and tr("Loading") or enabled and tr("Saved") or tr("Original")
end
function W:selectSkin(entry)
    if self.waiting or self.currentId==entry.id then return false end
    if self.actions.enable(self.player,entry.index)then self.waiting=true;self.yaw=0;self.zoom=1.08;self:refresh();return true end
    self.status=tr("Error");return false
end
function W:onCharacter(character)
    if self.waiting then return end
    if character.locked then self.status=tr("CharacterPackRequired");return end
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
function W:onLibrary()if not self.waiting and self.actions.refresh(self.player)then data=loadData();textures={};self:refresh()end end
function W:onRestore()self.actions.disable(self.player);if AimohPMXReadyRoom then AimohPMXReadyRoom.release(self.player)end;self.waiting=false;self.selected=nil;self:refresh()end
function W:onReset()if self.actions.reset(self.player)then self:refreshControls()end end
function W:onReload()if self.actions.reload(self.player)then self:refreshControls()end end
function W:update()
    ISCollapsableWindow.update(self)
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
    end
    if not self.folded and not self.foldStart and self.roomPage=="motions" then self:refreshMotionControls() end
    if not self.folded and not self.foldStart and self.roomPage=="appearance" and self.tab=="grip"
        and not self.waiting and AimohPMXReadyRoom.gripFamily(self.player)~=(self.gripFamily or "") then
        self:refreshControls(true)
    end
end
function W:prerender()
    local grip=self.roomPage=="appearance" and self.tab=="grip" and not self.folded and not self.foldStart
    if grip then
        self:drawRect(0,44,128,self.height-44,.94,.035,.050,.065)
        self:drawRect(self.rightX-8,44,self.width-self.rightX+8,self.height-44,.96,.035,.050,.065)
    else
        self:drawRect(3,4,self.width,self.height,.24,0,0,0)
        self:drawRect(0,0,self.width,self.height,.99,.035,.050,.065)
    end
    local background=image(data.background)
    if background and not grip and not self.folded and not self.foldStart then
        self:drawTextureScaled(background,1,44,self.width-2,self.height-45,1,1,1,1)
    end
    surface(self,1,0,self.width-2,44,1)
    self:drawRectBorder(0,0,self.width,self.height,.77,.29,.38,.44)
    self:drawRect(16,42,27,1,.7,.73,.48,.25)
    label(self,tr("Menu"),17,10,27,105)
    if self.folded or self.foldStart then return end
    local phase=self.pageFade and ease((getTimestampMs()-self.pageFade)/150) or 1
    self.tabDrawX=(self.tabFromX or self.tabToX)+(self.tabToX-(self.tabFromX or self.tabToX))*phase
    self.tabDrawWidth=(self.tabFromWidth or self.tabToWidth)+(self.tabToWidth-(self.tabFromWidth or self.tabToWidth))*phase
    self:drawRect(self.tabDrawX,38,self.tabDrawWidth,2,1,1,.71,.38)
    if self.roomPage=="motions" then return end
    if self.tab=="skins" and self.browsed then
        label(self,self.browsed.meta.skin,138,self.height-163,35,self.rightX-156)
    end
end
function W:render()
    if self.folded or self.foldStart or not self.pageFade then return end
    local phase=ease((getTimestampMs()-self.pageFade)/150)
    if phase==1 then self.pageFade=nil;return end
    self:drawRect(1,44,self.width-2,self.height-45,.38*(1-phase),.035,.052,.069)
end
function W:close()
    if self.motionSeek then self.motionSeek:cancel() end
    if self.motionSpeed then self.motionSpeed.dragInside=false end
    self:closeMotionPathDialog()
    if self.carouselPanel then self.carouselPanel.dragging=false;self.carouselPanel:setCapture(false)end
    self:clearContent();self.preview.dragging=false;self.preview:setCapture(false);self.sidebar.roomDown=false;self.sidebar:setCapture(false)
    if AimohPMXReadyRoom then AimohPMXReadyRoom.release(self.player)end
    self:setVisible(false);self:removeFromUIManager()
end
function W.open(player,actions)
    if not AimohPMXReadyRoom then player:Say(getText("ContextMenu_AimohPMX_JavaUnavailable"));return end
    data=loadData()
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
function W.closeAll()for _,window in pairs(W.instances)do window:close()end;W.instances={};data=nil;textures={}end
function W.closeFor(player)
    if not player then return end
    local index=player:getPlayerNum();if W.instances[index]then W.instances[index]:close();W.instances[index]=nil end
end
return W
