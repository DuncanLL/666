local function click(button)
    if button.visible and button.enabled~=false then button.onclick(button.target,button) end
end
local values,loads={},0
function AimohPMXReadyRoom.motionState()return {name=""}end
function AimohPMXReadyRoom.expressionRows(p,id)
    assert(p==actor and id==AimohPMXPreview.modelId(p))
    return {{index=7,group="ExpressionMouth",label="张嘴",value=values[id] or 0}}
end
function AimohPMXReadyRoom.expressionSet(p,id,index,value)
    assert(p==actor and index==7 and id==AimohPMXPreview.modelId(p))
    values[id]=value;return ""
end
function AimohPMXReadyRoom.expressionSnapshot(p,id)return tostring(values[id] or 0)end
function AimohPMXReadyRoom.expressionLoad(p,id,value)
    values[id]=tonumber(value);loads=loads+1;return ""
end

local function open()
    local ctx=context()
    Events.OnFillWorldObjectContextMenu.handlers[1](0,ctx,{},false)
    ctx.items[1].fn(actor)
    return AimohPMXWindow.instances[0]
end
local function slider(window)
    for _,widget in ipairs(window.widgets)do
        if widget.onValueChange then return widget end
    end
end

for _,size in ipairs({{1280,720},{800,480}})do
    screenWidth,screenHeight=size[1],size[2]
    actor.current=0;actor.data={untouched="keep"}
    local id=AimohPMXPreview.modelId(actor)
    local window=open()
    click(window.expressionButton)
    assert(window.tab=="expression" and window.expressionFooter.visible and #window.expressionSlotButtons==10)
    assert(window.content.y+window.content.height<window.expressionFooter.y)
    assert(window.expressionFooter.y+window.expressionFooter.height<window.expressionButton.y)
    local first=window.expressionSlotButtons[1]
    local last=window.expressionSlotButtons[10]
    assert(not first.enabled and not last.enabled and not first.roomActive)
    slider(window):setCurrentValue(74)
    assert(values[id]==.74)
    click(first);assert(actor.data.AimohPMXExpressions==nil)
    click(window.expressionSave);assert(first.enabled and not first.roomActive)
    click(first)
    local slots=actor.data.AimohPMXExpressions[id]
    assert(tonumber(slots["1"])==.74 and first.roomActive and not last.enabled)
    slider(window):setCurrentValue(20)
    click(first)
    assert(values[id]==.74 and tonumber(slots["1"])==.74 and slider(window):getCurrentValue()==74)
    slider(window):setCurrentValue(40)
    click(window.expressionSave);click(first)
    assert(tonumber(slots["1"])==.40 and first.roomActive)
    click(window.expressionSave);click(last)
    assert(tonumber(slots["10"])==.40 and last.roomActive)
    click(window.expressionSave);click(window.expressionSave)
    assert(not window.expressionSaving)
    click(window.expressionSave);window:setTab("parts");window:setTab("expression")
    assert(not window.expressionSaving and first.roomActive)
    click(window.expressionSave);window:setRoomPage("motions");window:setRoomPage("appearance")
    assert(not window.expressionSaving and first.roomActive)
    actor.current=1;window:refresh()
    assert(not first.roomActive and not last.enabled)
    actor.current=0;window:refresh()
    assert(first.roomActive and last.roomActive)
    window:onRestore()
    assert(not window.expressionFooter.visible and actor.data.untouched=="keep")
    assert(tonumber(actor.data.AimohPMXExpressions[id]["1"])==.40)
    actor.current=0
    AimohPMXWindow.closeFor(actor)
    window=open();click(window.expressionButton)
    assert(window.expressionSlotButtons[1].roomActive and window.expressionSlotButtons[10].roomActive)
    AimohPMXWindow.closeFor(actor)
end
assert(loads==2)
return true
