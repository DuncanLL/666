local get=getTexture
local ready=false
local requests={}
function getTexture(path)
 requests[path]=(requests[path]or 0)+1
 if not ready then return nil end
 return get(path)
end
local pictures={}
function UI:drawTextureScaled(t,...)pictures[t.path]=true end
function UI:drawTextureScaledAspect(t,...)pictures[t.path]=true end
function recordDraw(panel,kind,x,y,w,h,a,r,g,b,path)
 if kind=='image'or kind=='aspect'then pictures[path]=true end
end
local function tick()for _,fn in ipairs(Events.OnTick.handlers)do fn()end end
local ctx=context();Events.OnFillWorldObjectContextMenu.handlers[1](0,ctx,{},false);ctx.items[1].fn(ctx.items[1].target)
local w=AimohPMXWindow.instances[0]
local character=w.characters[1];w:onCharacter(character);w:update();paint(w)
local portrait=character.portrait;local card=w.browsed.meta.card
assert(portrait and card,'Fixture must use real catalog artwork')
assert(not pictures[portrait]and requests[portrait],'First unavailable image was not requested')
ready=true;paint(w)
assert(pictures[portrait]and pictures[card],'Transient texture miss poisoned the room cache')
assert(w.pageButton.enabled,'Parts unavailable after resource recovery')
w:setTab('parts');assert(#w.widgets>0,'Parts rows missing')
local actions=w.actions;local count=#w.characters
w:close()
local nextData={};for key,value in pairs(roomData)do nextData[key]=value end
nextData.models={};for id,meta in pairs(roomData.models)do nextData.models[id]=meta end
local firstId=w.browsed.id;local nextMeta={};for key,value in pairs(roomData.models[firstId])do nextMeta[key]=value end
nextMeta.portrait='room-fixture-new-catalog-portrait.png';nextData.models[firstId]=nextMeta;roomData=nextData
w=AimohPMXWindow.open(actor,actions);w:update();paint(w)
assert(w.characters[1].portrait==nextMeta.portrait and pictures[nextMeta.portrait],'Reopen retained stale catalog')
assert(#w.characters==count and pictures[portrait]and w.browsed,'Reopen lost room content')
w:setTab('parts');assert(#w.widgets>0 and w.pageButton.enabled,'Reopen left parts disabled')
AimohPMXWindow.closeAll();w=AimohPMXWindow.open(actor,actions);w:update();paint(w)
assert(#w.characters==count and w.browsed,'World lifecycle lost catalog')
w:close()
return 'PASS_ROOM_TRANSIENT_IMAGES_REOPEN_PARTS_AND_WORLD_LIFECYCLE'
