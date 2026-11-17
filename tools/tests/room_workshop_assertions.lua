local actions={}
local w=AimohPMXWindow.open(actor,actions)
local count=0
for _ in pairs(roomData.roomWorkshopItems)do count=count+1 end
assert(#w.characters>=count)
for _,character in ipairs(w.characters)do
 w:onCharacter(character)
 assert(character.locked)
 local id=roomData.roomWorkshopItems[character.name]
 if id then
  assert(w.workshopButton.visible)
  local before=#opened
  click(w.preview,w.workshopButton)
  assert(#opened==before and w.workshopPrompt and w.workshopPrompt.id==id)
  local prompt=w.workshopPrompt
  local x=w.preview.x+w.workshopButton.x+w.workshopButton.width/2
  local y=w.preview.y+w.workshopButton.y+w.workshopButton.height/2
  assert(hit(w,x,y)~=w.workshopButton,"modal does not block the first button")
  w:requestWorkshopPage()
  assert(#opened==before and w.workshopPrompt==prompt,"double click opened a page")
  click(prompt.card,prompt.cancel)
  assert(not w.workshopPrompt and #opened==before)
  click(w.preview,w.workshopButton);w:back()
  assert(not w.workshopPrompt and not w.closed and #opened==before)
  click(w.preview,w.workshopButton)
  prompt=w.workshopPrompt
  click(prompt.card,prompt.confirm)
  assert(#opened==before+1 and not w.workshopPrompt)
  assert(opened[#opened].url=="https://steamcommunity.com/sharedfiles/filedetails/?id="..id)
  click(prompt.card,prompt.confirm)
  assert(#opened==before+1,"stale confirmation opened twice")
  overlayEnabled=true
  click(w.preview,w.workshopButton);prompt=w.workshopPrompt
  click(prompt.card,prompt.confirm)
  assert(#opened==before+2 and opened[#opened].kind=="overlay" and opened[#opened].id==id)
  overlayEnabled=false
 else
  assert(character.locked and not w.workshopButton.visible)
 end
end
for _,character in ipairs(w.characters) do
 if roomData.roomWorkshopItems[character.name] then w:onCharacter(character);break end
end
click(w.preview,w.workshopButton)
local before=#opened
w:close()
assert(#opened==before and not w.workshopPrompt)
registryModels=allModels
w=AimohPMXWindow.open(actor,actions)
for _,c in ipairs(w.characters)do
 assert(not c.locked)
 w:onCharacter(c)
 assert(not w.workshopButton.visible,"loaded character shows missing-MOD action")
 assert(c.unlockRequired and c.recruitment and w.drawButtons[1].visible and w.drawButtons[1].enable,
  c.name.." must start locked and have a working recruitment button")
 assert(not w:selectSkin(c.baseSkin),c.name.." can be equipped before recruitment")
 w:requestWorkshopPage()
 assert(not w.workshopPrompt)
end
local newcomer
for _,c in ipairs(w.characters)do
 if c.recruitment.pool=="R116Soppo" then newcomer=c;break end
end
assert(newcomer and newcomer.recruitment.pool==newcomer.baseSkin.meta.pack)
w:onCharacter(newcomer)
actor:getModData().R116ReadyRoomWallet.targeted=1
function ZombRand()return 0 end
w:requestCharacterDraw(1)
assert(w.drawAnimation.won and economy.characterUnlocked(actor,newcomer.recruitment.pool))
w.drawAnimation=nil
w:refresh()
assert(not w.byCharacter[newcomer.name].unlockRequired)
for _,c in ipairs(w.characters)do
 if c.name~=newcomer.name then assert(c.unlockRequired) end
end
do
 local oldClock,oldMouse,oldElement=getTimestampMs,isMouseButtonDown,ISUIElement
 ISUIElement=UI
 local clock,held,over=20000,false,true
 function getTimestampMs()return clock end
 function isMouseButtonDown(button)return held and button==0 end
 local purchases,lastKey=0,nil
 local function plus(window,index)
  window.openExchange=function(_,key)purchases=purchases+1;lastKey=key end
  local button=window.resourceButtons[index or 1].children[1]
  button.isMouseOver=function()return over end
  return button
 end
 local function down(button)held=true;button:onMouseDown(5,10)end
 local function up(button)held=false;button:onMouseUp(5,10)end

 assert(not w.motionTab.visible and not w.motionLibraryBox.visible and not w.motionBox.visible)
 w:setRoomPage("motions");w:requestMotionPage()
 assert(w.roomPage=="appearance","hidden VMD tool opened")
 local compactX,compactY=w.physicsResetButton.x,w.physicsResetButton.y
 local targeted=plus(w)
 assert(targeted.tooltip==roomData.text.RoomTargetedPermit)
 down(targeted);clock=clock+180;up(targeted)
 assert(purchases==1 and lastKey=="targeted" and not w.motionTab.visible)
 down(targeted);clock=clock+9999;targeted:update()
 assert(not w.motionTab.visible,"gesture completed before ten seconds")
 clock=clock+1;targeted:update()
 assert(w.motionTab.visible and w.motionTab.x==compactX and w.motionTab.y==compactY)
 assert(w.physicsResetButton.y>compactY,"list did not reflow around the revealed button")
 clock=clock+10000;targeted:update()
 assert(w.motionTab.visible,"one hold toggled the entry twice")
 up(targeted)
 assert(purchases==1,"a completed long press opened the permit exchange")

 local canEditCurrent=w.canEditCurrent
 w.canEditCurrent=function()return true end
 w.getX=function(self)return self.x end
 w.getY=function(self)return self.y end
 local motionLibrary,motionState=AimohPMXReadyRoom.motionLibrary,AimohPMXReadyRoom.motionState
 AimohPMXReadyRoom.motionLibrary=function()return {} end
 AimohPMXReadyRoom.motionState=function()return {name=""} end
 w:requestMotionPage()
 assert(w.roomPage=="motions" and w.motionLibraryBox.visible and w.motionBox.visible)
 w.canEditCurrent=canEditCurrent
 w:back()
 assert(w.roomPage=="appearance" and w.motionTab.visible)
 w:close()
 w=AimohPMXWindow.open(actor,actions)
 w:onCharacter(w.byCharacter[newcomer.name])
 assert(w.motionTab.visible,"closing the window discarded the per-player session toggle")

 targeted=plus(w)
 down(targeted);clock=clock+10000;up(targeted)
 assert(not w.motionTab.visible and w.physicsResetButton.x==compactX and w.physicsResetButton.y==compactY)
 assert(purchases==1,"release after ten seconds opened the permit exchange")
 down(targeted);clock=clock+5000
 over=false;targeted:onMouseMoveOutside(1,0)
 over=true;clock=clock+10000;targeted:update()
 assert(not w.motionTab.visible,"leaving the button failed to cancel the timer")
 targeted:onMouseUpOutside(0,0);held=false

 local outfit=plus(w,2)
 down(outfit);clock=clock+10000;outfit:update();up(outfit)
 assert(not w.motionTab.visible and purchases==2 and lastKey=="outfit")
 down(targeted);clock=clock+10000;targeted:update();up(targeted)
 assert(w.motionTab.visible)
 AimohPMXWindow.closeAll()
 w=AimohPMXWindow.open(actor,actions)
 w:onCharacter(w.byCharacter[newcomer.name])
 assert(not w.motionTab.visible,"room session reset did not restore the hidden default")
 AimohPMXReadyRoom.motionLibrary,AimohPMXReadyRoom.motionState=motionLibrary,motionState
 getTimestampMs,isMouseButtonDown,ISUIElement=oldClock,oldMouse,oldElement
end
local groupCount,groupLabel,optionCount,optionLabel=AimohPMXPreview.groupCount,AimohPMXPreview.groupLabel,
 AimohPMXPreview.optionCount,AimohPMXPreview.optionLabel
AimohPMXPreview.groupCount=function()return 3 end
AimohPMXPreview.groupLabel=function(_,i)return ({"眼睛",roomData.text.Head,"配饰"})[i+1]end
AimohPMXPreview.optionCount=function()return 2 end
AimohPMXPreview.optionLabel=function(_,group,option)
 if group==0 then return ({roomData.text.EyeOpen,roomData.text.EyeClosed})[option+1] end
 return "选项"
end
local main=w:partRows(false)
local extra=w:partRows(true)
local visible={}
for _,rows in ipairs({main,extra})do
 for _,row in ipairs(rows)do if row.index then visible[row.index]=true end end
end
assert(not visible[0] and visible[1] and visible[2],
 tostring(visible[0])..","..tostring(visible[1])..","..tostring(visible[2]))
AimohPMXPreview.groupCount,AimohPMXPreview.groupLabel,AimohPMXPreview.optionCount,AimohPMXPreview.optionLabel=
 groupCount,groupLabel,optionCount,optionLabel
w:close()

client=true
local commands={}
function sendClientCommand(p,module,command,args)
 assert(p==actor and module=="GFL2Exilium")
 commands[#commands+1]={command=command,args=args}
end
local equipped=0
actions.enable=function()equipped=equipped+1;return true end
w=AimohPMXWindow.open(actor,actions)
w:onCharacter(w.byCharacter[newcomer.name])
w:requestCharacterDraw(1)
assert(#commands==0 and not w.drawAnimation)
Events.OnTick.handlers[1]()
assert(commands[1].command=="Request")
local function server(command,args)
 args.version=1;args.player=actor:getOnlineID()
 Events.OnServerCommand.handlers[1]("GFL2Exilium",command,args)
end
server("Wallet",{request=0,wallet={targeted=1}})
w:requestCharacterDraw(1)
w:requestCharacterDraw(1)
assert(#commands==2 and commands[2].command=="DrawCharacter" and not w.drawAnimation)
assert(commands[2].args.target==newcomer.baseSkin.id and commands[2].args.count==1)
server("Transaction",{request=1,result={status="unlocked",results={true}},
 wallet={targeted=0,recruitment={owned={R116Soppo=true}}}})
assert(w.drawAnimation.won and economy.balance(actor,"targeted")==0)
w.drawAnimation=nil;w:refresh()
assert(w:selectSkin(w.byCharacter[newcomer.name].baseSkin))
assert(#commands==3 and commands[3].command=="Wear" and equipped==0)
server("Transaction",{request=2,result={status="ok"},wallet={recruitment={owned={R116Soppo=true}}}})
assert(equipped==1)
w:close()
return "PASS Workshop links, recruitment/equip and the hidden VMD entry"
