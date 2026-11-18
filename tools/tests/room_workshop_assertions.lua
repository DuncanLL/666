local actions={}
local w=GFL2RoomWindow.open(actor,actions)
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
w=GFL2RoomWindow.open(actor,actions)
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
local function fontHeight(font)
 return font==UIFont.Medium and stockFontMediumHeight or stockFontHeight
end
local function localeMetrics()
 return {
  MeasureStringX=function(_,font,value)return measureStockFont(value,font or UIFont.Small)end,
  getFontHeight=function(_,font)return fontHeight(font)end
 }
end
do
 local oldScene,oldScripts=ISUI3DScene,getScriptManager
 local oldMetrics=getTextManager
 if roomTranslations then getTextManager=localeMetrics end
 ISUI3DScene=UI:derive("WeaponPreviewFixture")
 function ISUI3DScene:instantiate()
  local scene=self
  self.javaObject={
   fromLua1=function(_,command,value)
    if command=="getObjectTranslation" then return {set=function()end} end
    assert(command~="zoom","pointer-centred zoom can pan a weapon out of its viewport")
    scene[command]=value
   end,
   fromLua2=function(_,command,id,model)scene.fixtureModel=model end,
   fromLua3=function(_,command,pitch)scene.viewPitch=pitch end,
  }
 end
 function ISUI3DScene:setView()end
 local roll
 local modelScript={getAttachmentById=function(_,id)
  if id=="muzzle" and roll then return {getRotate=function()return {z=function()return roll end}end} end
 end}
 function getScriptManager()return {FindItem=function()return true end,getModelScript=function()return modelScript end}end
 local card=w.weaponCard
 local originalProgress=economy.weaponProgress
 local originalCenter=GFL2ReadyRoom.centerWeaponPreview
 local ready=false
 GFL2ReadyRoom.centerWeaponPreview=function(id,offset)
  if ready then offset:set(0,0,0) end
  return ready
 end
 local drawIcon=card.drawSubTexture
 local cardImage
 card.drawSubTexture=function(b,tex,sx,sy,sw,sh,x,y,width,height)
  assert(x>=31.99 and y>=23.99 and height>0 and math.abs(x+width/2-b.width/2)<.01 and math.abs(y+height/2-b.height/2)<.01)
  cardImage=tex.path
 end
 local tested=0
 for _,c in ipairs(w.characters) do
  w:onCharacter(c)
  local weapon=w.signatureWeapon
  if weapon then
   roll=tested%2==0 and -180 or nil
   local wasLocked=c.unlockRequired
   c.unlockRequired=false;w:layoutOverview()
   cardImage=nil;card:render();assert(cardImage==weapon.card)
   assert(card.visible);click(w,card)
   local scene=w.weaponPreview
   ready=true;scene:prerender();ready=false;assert(rawget(scene,"prerender")==nil)
   local info=w.content
   assert(scene.fixtureModel==weapon.id and scene.viewPitch==-82 and info.visible and scene.width==scene.height)
   assert(math.abs(scene.x+scene.width/2-w.width/2)<=.5 and math.abs(scene.y+scene.height/2-w.height/2)<=.5)
   assert(info.x+info.width<=scene.x or info.y>=scene.y+scene.height)
   assert(scene.y>=w:titleBarHeight() and scene.y+scene.height<=w.drawButtons[1].y-99)
   assert(info.y>=w:titleBarHeight() and info.y+info.height<=w.height and info:getScrollHeight()>=info.height)
   local limit=scene.setMaxZoom
   scene:onMouseWheel(-100);assert(scene.setZoom==limit)
   scene:onMouseWheel(100);assert(scene.setZoom==1)
   scene:onMouseDown();scene:onMouseMove(900,500);assert(scene.orbit and scene.capture)
   local skin=weaponSkins.available(weapon.id)[1]
   roll=0
   economy.weaponProgress=function()return {acquired=true,skin=skin.id} end
   w.weaponSkinOpen=true;w:layoutOverview()
   local painted=w.weaponPreview
   assert(painted~=scene and not scene.capture and painted.fixtureModel==skin.model)
   assert(painted.viewPitch==98);painted:onMouseDown();painted:onMouseMove(5,0);assert(painted.viewPitch==98)
   assert(w.weaponSkinPanel.visible and w.weaponSkinPanel.y+w.weaponSkinPanel.height<=info.y-8)
   assert((painted.x>=info.x+info.width or info.y>=painted.y+painted.height) and painted.y+painted.height<=w.weaponClaimButton.y-8)
   economy.weaponProgress=originalProgress
   c.unlockRequired=wasLocked
   w:back();assert(not painted.visible and not painted.capture)
   tested=tested+1
  end
 end
 assert(tested==24)
 card.drawSubTexture=drawIcon
 GFL2ReadyRoom.centerWeaponPreview=originalCenter
 getTextManager=oldMetrics
 ISUI3DScene,getScriptManager=oldScene,oldScripts
end
if roomTranslations then
 local oldMetrics=getTextManager
 getTextManager=localeMetrics
 for _,button in ipairs({w.gripAimButton,w.gripButton,w.expressionButton,w.motionTab,w.restoreButton,w.bodyPhysicsButton,w.physicsResetButton,w.drawButtons[1],w.drawButtons[2]}) do
  local originalText,originalCentre,originalZoom=button.drawText,button.drawTextCentre,button.drawTextZoomed
  local function check(value,x,y,zoom,font)
   local top,bottom=0,button.height
   if y<0 and (button.roomLook=="drawOnce" or button.roomLook=="drawTen") then top,bottom=-29,-4 end
   assert(y>=top and y+fontHeight(font)*zoom<=bottom+.01,"button text exceeds height for "..value)
   assert(x>=-.01 and x+measureStockFont(value,font)*zoom<=button.width+.01,"button text exceeds width for "..value)
   if button.roomTile then
    local reserved=button==w.bodyPhysicsButton and 50 or 27
    assert(value==button.roomTitle and x>=40 and x+measureStockFont(value,font)*zoom<=button.width-reserved+.01,
     "action label collides with an icon or was shortened: "..value)
   end
  end
  function button:drawText(value,x,y,_,_,_,_,font) check(value,x,y,1,font) end
  function button:drawTextCentre(value,x,y,_,_,_,_,font) check(value,x-measureStockFont(value,font)/2,y,1,font) end
  function button:drawTextZoomed(value,x,y,zoom,_,_,_,_,font) check(value,x,y,zoom,font) end
  button:render()
  button.drawText,button.drawTextCentre,button.drawTextZoomed=originalText,originalCentre,originalZoom
 end
 for _,c in ipairs(w.characters) do
  local expected=localizedBiographies and localizedBiographies[c.name] or roomData.roomCharacterDescriptions[c.name]
  local locked=c.unlockRequired
  c.unlockRequired=false
  w:layoutBiography(c)
  local panel=w.biographyPanel
  assert(panel.visible and panel.y+panel.height<=w.height-14,"profile exceeds the room for "..c.name)
  if type(expected)=="table" then
   assert(table.concat(panel.lines)==table.concat(expected),"profile lost text for "..c.name)
  else
   assert(table.concat(panel.lines):gsub("%s+","")==expected:gsub("%s+",""),"profile lost text for "..c.name)
   for _,line in ipairs(panel.lines) do
    assert(measureStockFont(line)<=panel.textWidth,"profile line exceeds the panel for "..c.name)
   end
  end
  panel:onMouseWheel(1000)
  assert(panel:getYScroll()==math.min(0,panel.height-panel:getScrollHeight()),
   "profile cannot scroll to the end for "..c.name)
  c.unlockRequired=locked
  w:layoutBiography(c)
  assert(not panel.visible)
 end
 getTextManager=oldMetrics
end
if localizedBiographies then
 local oldMetrics=getTextManager
 getTextManager=localeMetrics
 local oldRect,oldText=w.drawRect,w.drawText
 for _,remaining in ipairs({1,180}) do
  local bar
  local text={}
  function w:drawRect(x,y,width,height)
   if not bar or width>bar.width then bar={x=x,y=y,width=width,height=height} end
   assert(x>=0 and x+width<=self.width,"guarantee panel exceeds the window")
   assert(y+height<=self.drawButtons[1].y-29,"guarantee panel overlaps the draw cost")
  end
  function w:drawText(value,x,y,_,_,_,_,font)
   assert(y>=bar.y and y+fontHeight(font)<=bar.y+bar.height,"guarantee text exceeds panel height")
   assert(x>=bar.x and x+measureStockFont(value,font)<=bar.x+bar.width,"guarantee text exceeds panel width")
   text[#text+1]=value
  end
  w:drawElitePity(remaining)
  assert(table.concat(text)==tostring(remaining)..getText("ContextMenu_GFL2_PityCounter"))
 end
 w.drawRect,w.drawText=oldRect,oldText
 local checked=0
 for _,c in ipairs(w.characters)do
  local expected=localizedBiographies[c.name]
  if expected then
   local lines,tags,previousY,tagBottom={}, {},nil,nil
   local pack=c.baseSkin.meta.pack
   local expectedTags=roomTranslations["UI_GFL2_RecruitTags_"..pack]
   local expectedPhase=roomTranslations["UI_GFL2_RecruitPhase_"..pack]
   local foundPhase=false
   local oldDrawText,oldDrawImage=w.drawText,w.drawTextureScaled
   function w:drawTextureScaled(t,x,y,width,height,...)
    if t.path==c.recruitment.banner then
     assert(y>=self:titleBarHeight(),"recruitment banner overlaps the title for "..c.name.." at y="..y)
    end
   end
   w.drawText=function(_,value,x,y,r)
    local bottom=y+stockFontHeight
    local maxWidth=(bottom<=w.drawButtons[1].y-64 and w.width or w.rightX)-x-24
    if value==expectedPhase then foundPhase=true end
    if r==.89 then
     tags[#tags+1]=value
     tagBottom=y+stockFontHeight
     assert(measureStockFont(value)<=maxWidth,"recruitment tags exceed the card for "..c.name)
    end
    if r~=.84 then return end
    assert(y>=w:titleBarHeight() and bottom<=w.height-42,"biography exceeds the room for "..c.name)
    assert(not tagBottom or y>=tagBottom,"biography overlaps the recruitment tags for "..c.name)
    assert(not previousY or y-previousY>=stockFontHeight,"biography overlaps for "..c.name)
    assert(measureStockFont(value)<=maxWidth,"biography line exceeds the card for "..c.name)
    previousY=y
    lines[#lines+1]=value
   end
   w:drawLockedCharacterInfo(c)
   w.drawText=oldDrawText
   w.drawTextureScaled=oldDrawImage
   assert(#lines>0 and table.concat(lines):gsub("%s+","")==expected:gsub("%s+",""),
    "biography lost text for "..c.name)
   assert(foundPhase and table.concat(tags):gsub("%s+","")==expectedTags:gsub("%s+",""),
    "recruitment labels lost text for "..c.name)
   checked=checked+1
  end
 end
 assert(checked==24)
 local oldScripts=getScriptManager
 function getScriptManager()return {getModelScript=function(_,id)return id end}end
 local paintCount=0
 for _,weapon in pairs(signatureWeapons.byId) do
  local variants=weaponSkins.available(weapon.id)
  w:layoutWeaponSkins(weapon,variants,true,"")
  assert(#w.weaponSkinRows==#variants+1)
  for i,variant in ipairs(variants) do
   local row=w.weaponSkinRows[i+1]
   local expected=roomTranslations["UI_GFL2_WeaponSkin_"..variant.id]
   assert(row.roomTitle==expected and row.skinId==variant.id)
   local lines={}
   function row:drawTextCentre(value,_,y)
    assert(y>=0 and y+stockFontHeight<=self.height,"paint title exceeds row height")
    assert(measureStockFont(value)<=self.width-16,"paint title exceeds row width")
    lines[#lines+1]=value
   end
   row:render()
   assert(table.concat(lines):gsub("%s+","")==expected:gsub("%s+",""),"paint title lost text for "..variant.id)
   local previous=w.weaponSkinRows[i]
   assert(row.y>=previous.y+previous.height)
   paintCount=paintCount+1
  end
 end
 assert(paintCount==24)
 w:layoutWeaponSkins(nil,{},false,"")
 getScriptManager=oldScripts
 if localizedOutfitDescriptions then
  local tab,browsed,character=w.tab,w.browsed,w.character
  local oldText,oldPicture=w.drawText,w.drawTextureScaled
  local shown,pools=0,0
  for id,meta in pairs(roomData.models) do
   local expected=localizedOutfitDescriptions[id]
   local title=roomTranslations["UI_GFL2_Outfit_"..id]
   local titleArt=(roomData.roomLabels[title or ""] or roomData.labels[title or ""])
   local pool=roomData.roomOutfitPools[id]
   w.character=meta.character
   w.browsed={id=id,meta=meta,isDefault=meta.isDefault,outfitPool=pool}
   for _,mode in ipairs(pool and {"skins","outfitDraw"} or {"skins"}) do
    local lines,titleLines,previousY={}, {},nil
    local titleImage=false
    local titleBottom=0
    w.tab=mode
    function w:drawText(value,x,y,r)
     if r==.94 then
      titleLines[#titleLines+1]=value
      titleBottom=y+stockFontHeight
      assert(y>=self:titleBarHeight(),"outfit title overlaps the top bar for "..id)
      assert(x+measureStockFont(value)<=self.rightX-24,"outfit title obstructs the controls for "..id)
     end
     if r~=.84 then return end
     assert(y>=self:titleBarHeight() and y+stockFontHeight<=self.height-20,
      "outfit description exceeds the window for "..id.." on "..mode)
     assert(x+measureStockFont(value)<=self.rightX-24,"outfit description obstructs the controls for "..id)
     assert(titleBottom<=y,"outfit description overlaps the title for "..id)
     assert(not previousY or y-previousY>=stockFontHeight,"outfit description overlaps itself for "..id)
     previousY=y
     lines[#lines+1]=value
    end
    function w:drawTextureScaled(tex,x,y,width,height)
     if titleArt and tex.path==titleArt.path then titleImage=true end
     if mode=="outfitDraw" and tex.path==pool.banner then
      assert(y>=self:titleBarHeight(),"outfit banner overlaps the top bar for "..id)
     end
    end
    w:prerender()
    assert(not title or titleImage or table.concat(titleLines):gsub("%s+","")==title:gsub("%s+",""),
     "outfit title lost text for "..id)
    assert(not expected or (#lines>0 and table.concat(lines):gsub("%s+","")==expected:gsub("%s+","")),
     "outfit description lost text for "..id)
    if mode=="outfitDraw" then pools=pools+1 end
   end
   shown=shown+1
  end
  assert(shown==69 and pools>0)
  w.tab,w.browsed,w.character=tab,browsed,character
  w.drawText,w.drawTextureScaled=oldText,oldPicture
 end
 getTextManager=oldMetrics
end
local newcomer
for _,c in ipairs(w.characters)do
 if c.recruitment.pool=="GFL2Soppo" then newcomer=c;break end
end
assert(newcomer and newcomer.recruitment.pool==newcomer.baseSkin.meta.pack)
w:onCharacter(newcomer)
actor:getModData().GFL2ReadyRoomWallet.targeted=1
function ZombRand()return 0 end
w:requestCharacterDraw(1)
assert(w.drawAnimation.won and economy.characterUnlocked(actor,newcomer.recruitment.pool))
w.drawAnimation=nil
w:refresh()
assert(not w.byCharacter[newcomer.name].unlockRequired)
for _,c in ipairs(w.characters)do
 if c.name~=newcomer.name then assert(c.unlockRequired) end
end
if roomTranslations then
 local oldEdit,oldLabel=w.canEditCurrent,w.addLabel
 local oldSelection=w.selected
 local oldRows,oldState=GFL2ReadyRoom.expressionRows,GFL2ReadyRoom.motionState
 local labels={}
 w.canEditCurrent=function()return true end
 w.selected={meta={}}
 w.addLabel=function(window,value,...)
  labels[value]=true
  return oldLabel(window,value,...)
 end
 GFL2ReadyRoom.motionState=function()return {name=""} end
 GFL2ReadyRoom.expressionRows=function()
  return {{index=7,group="ExpressionEyes",label=testExpression,value=.25}}
 end
 w:setTab("expression")
 local expected=roomTranslations["UI_GFL2_ExpressionControl_"..testExpression]
 local shown={}
 for name in pairs(labels) do shown[#shown+1]=name end
 assert(expected and labels[expected],tostring(expected).." in "..table.concat(shown,", "))
 local slider
 for _,widget in ipairs(w.widgets) do if widget.roomSlider then slider=widget end end
 assert(slider and slider.currentValue==25)
 assert(w.content.y+w.content.height+6==w.expressionFooter.y and w.expressionFooter.y+w.expressionFooter.height<=w.height,
  "short expression lists must place the save controls directly below their rows")
 assert(w.content.height<w.height*.5,"a short expression list left a full-height empty panel")
 local oldPhysics,oldToggle=GFL2CharacterRuntime.modelPhysicsEnabled,w.actions.bodyPhysics
 local physics=true
 GFL2CharacterRuntime.modelPhysicsEnabled=function()return physics end
 w.actions.bodyPhysics=function(_,on)physics=on;return true end
 w:setTab("overview")
 assert(w.bodyPhysicsButton.roomActive)
 w:toggleBodyPhysics()
 assert(not physics and not w.bodyPhysicsButton.roomActive)
 GFL2CharacterRuntime.modelPhysicsEnabled,w.actions.bodyPhysics=oldPhysics,oldToggle
 w:layoutBiography(w.byCharacter[w.character])
 assert(w.bodyPhysicsButton.visible and w.bodyPhysicsButton.width==w.expressionButton.width)
 assert(w.bodyPhysicsButton.y>=w.expressionButton.y+w.expressionButton.height)
 assert(w.skinButton.x==w.overviewX and w.skinButton.x+w.skinButton.width<w.wearButton.x)
 assert(w.biographyPanel.y>=w.physicsResetButton.y+w.physicsResetButton.height)
 local bottom=w.weaponCard.visible and w.weaponCard.y or w.wearButton.y
 assert(w.biographyPanel.y+w.biographyPanel.height<=bottom-10,"biography covers the bottom actions")
 w.canEditCurrent,w.addLabel=oldEdit,oldLabel
 w.selected=oldSelection
 GFL2ReadyRoom.expressionRows,GFL2ReadyRoom.motionState=oldRows,oldState
 w:setTab("overview")
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
 w:updateTabMotion()
 local compactX,compactY=w.physicsResetButton.x,w.physicsResetButton.y
 local targeted=plus(w)
 local permit=(getTextOrNull and getTextOrNull("UI_GFL2_RoomTargetedPermit")) or roomData.text.RoomTargetedPermit
 local motion=(getTextOrNull and getTextOrNull("UI_GFL2_RoomMotion")) or roomData.text.RoomMotion
 assert(targeted.tooltip==permit and w.motionTab.roomTitle==motion)
 down(targeted);clock=clock+180;up(targeted)
 assert(purchases==1 and lastKey=="targeted" and not w.motionTab.visible)
 down(targeted);clock=clock+5000
 local chip=targeted.parent
 local oldRect=chip.drawRect
 local progress
 chip.drawRect=function(_,_,y,width,height,alpha)
  if y==chip.height-2 and height==2 and alpha==.92 then progress=width end
 end
 chip:prerender()
 assert(progress==(chip.width-4)/2,"permit hold meter must follow the real ten-second timer")
 chip.drawRect=oldRect
 clock=clock+4999;targeted:update()
 assert(not w.motionTab.visible,"gesture completed before ten seconds")
 clock=clock+1;targeted:update()
 assert(w.motionTab.visible and w.motionTab.x==w.expressionButton.x and w.motionTab.y>=w.expressionButton.y+w.expressionButton.height)
 assert(w.physicsResetButton.y>compactY,"list did not reflow around the revealed button")
 clock=clock+10000;targeted:update()
 assert(w.motionTab.visible,"one hold toggled the entry twice")
 up(targeted)
 assert(purchases==1,"a completed long press opened the permit exchange")

 local canEditCurrent=w.canEditCurrent
 w.canEditCurrent=function()return true end
 w.getX=function(self)return self.x end
 w.getY=function(self)return self.y end
 local motionLibrary,motionState=GFL2ReadyRoom.motionLibrary,GFL2ReadyRoom.motionState
 local playback={name="",frame=0,endFrame=240,speed=1,revision=3}
 GFL2ReadyRoom.motionLibrary=function()return {"first.vmd","second.vmd"} end
 GFL2ReadyRoom.motionState=function()return playback end
 w:requestMotionPage()
 assert(w.roomPage=="motions" and w.motionLibraryBox.visible and w.motionBox.visible)
 local list=w.motionList
 list.isMouseOver=function()return true end
 list.getMouseX=function()return 10 end
 list.getMouseY=function()return list.itemheight+5 end
 local alpha=0
 list.drawRect=function(_,_,_,_,_,a)alpha=a end
 local function drawSecond()
  alpha=0;list:doDrawItem(list.itemheight,list.items[2],false);return alpha
 end
 list:onMouseMove(0,0);assert(drawSecond()==0)
 clock=clock+55;local entering=drawSecond()
 assert(entering>0 and entering<.76 and list.selected==1)
 list:onMouseMoveOutside(0,0);assert(drawSecond()==entering)
 clock=clock+60;assert(drawSecond()<entering)
 clock=clock+150;assert(drawSecond()==0 and w.motionSelected=="first.vmd")
 local seek=w.motionSeek
 w.motionSpeed:onMouseDown(20,10)
 assert(not w.motionSpeed.dragInside,"disabled speed control started a drag")
 local seekCalls=0
 local oldCommand=GFL2ReadyRoom.motionCommand
 GFL2ReadyRoom.motionCommand=function(_,action,arg)
  assert(action=="seek" and math.abs(tonumber(arg:match(":(.+)"))-.75)<.001)
  seekCalls=seekCalls+1;return ""
 end
 assert(not seek:onMouseDown(10,22) and not seek.dragInside)
 playback.name="first.vmd";w:refreshMotionControls()
 seek.isMouseOver=function()return true end
 seek.getMouseX=function()return seek.sliderBarDim.x+seek.sliderBarDim.w*.5 end
 seek.getMouseY=function()return 24 end
 local marker=0
 seek.drawRect=function(_,_,_,width,_,a)if width==1 then marker=a end end
 seek:render();clock=clock+115;seek:render()
 assert(marker>.7 and seek.currentValue==0 and seekCalls==0,"hover must preview a position without seeking")
 assert(seek:onMouseDown(seek:getMouseX(),24) and seek.capture)
 assert(seek:onMouseUp(seek.sliderBarDim.x+seek.sliderBarDim.w*.75) and seekCalls==1 and not seek.capture)
 assert(seek:onMouseDown(seek:getMouseX(),24));playback.revision=4;w:refreshMotionControls()
 assert(not seek.dragInside and not seek:onMouseUp(20) and seekCalls==1)
 GFL2ReadyRoom.motionCommand=oldCommand
 w.canEditCurrent=canEditCurrent
 w:back()
 assert(w.roomPage=="appearance" and w.motionTab.visible)
 w:close()
 w=GFL2RoomWindow.open(actor,actions)
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
 down(outfit);clock=clock+180;up(outfit)
 assert(not w.motionTab.visible and purchases==2 and lastKey=="outfit")
 local characterBefore,outfitBefore,weaponBefore={},{},{}
 for _,character in ipairs(w.characters)do
  if character.recruitment then
   local key=character.recruitment.pool
   characterBefore[key]=economy.characterUnlocked(actor,key)
  end
  for _,skin in ipairs(character.skins)do outfitBefore[skin.id]=economy.outfitOwned(actor,skin.id) end
 end
 for id in pairs(signatureWeapons.byId)do weaponBefore[id]=economy.weaponProgress(actor,id).acquired==true end
 local permits=economy.balance(actor,"outfit")
 down(outfit);clock=clock+9999;outfit:update()
 assert(not economy.outfitOwned(actor,"not-yet-owned"),"recording unlock started before ten seconds")
 clock=clock+1;outfit:update()
 for key in pairs(characterBefore)do assert(economy.characterUnlocked(actor,key)) end
 for id in pairs(outfitBefore)do assert(economy.outfitOwned(actor,id)) end
 for id in pairs(weaponBefore)do assert(economy.weaponProgress(actor,id).acquired) end
 clock=clock+10000;outfit:update()
 assert(economy.outfitOwned(actor,"not-yet-owned"),"one hold toggled recording twice")
 up(outfit);assert(purchases==2 and not w.motionTab.visible)
 down(outfit);clock=clock+10000;up(outfit)
 for key,owned in pairs(characterBefore)do assert(economy.characterUnlocked(actor,key)==owned) end
 for id,owned in pairs(outfitBefore)do assert(economy.outfitOwned(actor,id)==owned) end
 for id,owned in pairs(weaponBefore)do assert((economy.weaponProgress(actor,id).acquired==true)==owned) end
 assert(not economy.outfitOwned(actor,"not-yet-owned") and economy.balance(actor,"outfit")==permits)
 assert(purchases==2,"recording holds opened the permit exchange")
 local railWindow={content=UI:new(0,0,300,340),fullHeight=700,widgets={},browseId="one",tab="skins",
  updateWear=function()end,previewEntry=function(self,e)self.browsed=e end}
 local outfits={{id="one",meta={}},{id="two",meta={},outfitLocked=true,outfitPool={}},{id="three",meta={}}}
 GFL2RoomWindow.carousel(railWindow,{skins=outfits})
 local rail=railWindow.carouselPanel
 rail:onMouseWheel(1)
 assert(railWindow.browsed.id=="two" and railWindow.tab=="skins")
 clock=clock+60;rail:onMouseWheel(1)
 assert(rail.index==3 and rail.slideFrom>1 and rail.slideFrom<2,"interrupted scroll snapped to the old target")
 clock=clock+200;rail:onMouseDown(150,160);rail:onMouseMove(90,0);rail:onMouseUp(240,160)
 assert(rail.index==2 and rail.slideFrom>2 and rail.slideFrom<3,"drag did not settle from the pointer position")
 down(targeted);clock=clock+10000;targeted:update();up(targeted)
 assert(w.motionTab.visible)
 GFL2RoomWindow.closeAll()
 w=GFL2RoomWindow.open(actor,actions)
 w:onCharacter(w.byCharacter[newcomer.name])
 assert(not w.motionTab.visible,"room session reset did not restore the hidden default")
 GFL2ReadyRoom.motionLibrary,GFL2ReadyRoom.motionState=motionLibrary,motionState
 getTimestampMs,isMouseButtonDown,ISUIElement=oldClock,oldMouse,oldElement
end
local groupCount,groupLabel,optionCount,optionLabel=GFL2CharacterRuntime.groupCount,GFL2CharacterRuntime.groupLabel,
 GFL2CharacterRuntime.optionCount,GFL2CharacterRuntime.optionLabel
GFL2CharacterRuntime.groupCount=function()return 3 end
GFL2CharacterRuntime.groupLabel=function(_,i)return ({"眼睛",roomData.text.Head,"配饰"})[i+1]end
GFL2CharacterRuntime.optionCount=function()return 2 end
GFL2CharacterRuntime.optionLabel=function(_,group,option)
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
if testPart then
 local previousMetrics=getTextManager
 function getTextManager()
  return {
   MeasureStringX=function(_,_,value)return measureStockFont(value)end,
   getFontHeight=function()return stockFontHeight end
  }
 end
 local function groupKey(value)return "UI_GFL2_PartGroup_"..value end
 local function optionKey(value)return "UI_GFL2_PartOption_"..value end
 local addLabel,addTile,selected,choice=w.addLabel,w.addTile,w.selected,w.actions.choice
 local selectedOption=GFL2CharacterRuntime.selectedOption
 local originals=testPart.options
 GFL2CharacterRuntime.groupLabel=function()return testPart.group end
 GFL2CharacterRuntime.optionLabel=function(_,_,option)return originals[option+1] end
 GFL2CharacterRuntime.selectedOption=function()return 1 end
 w.selected={meta={partImages={[testPart.group]={[originals[1]]="original-image",[originals[2]]="alternate-image"}}}}
 local labels,tiles,choices={},{},{}
 function w:addLabel(value,y,height)
  labels[#labels+1]={value=value,y=y,height=height}
 end
 function w:addTile(x,y,width,height,title,path,active,callback)
  tiles[#tiles+1]={title=title,path=path,active=active,y=y,height=height}
  callback(self)
 end
 w.actions.choice=function(_,group,option)
  assert(group==2)
  choices[#choices+1]=option
  return false
 end
 local bottom=w:drawGroups({{label=testPart.group,index=2},{label=testPart.group,index=2}},0,w.rightWidth-18)
 assert(#labels==2 and #tiles==4 and #choices==4)
 assert(labels[1].value==roomTranslations[groupKey(testPart.group)])
 assert(tiles[1].title==roomTranslations[optionKey(originals[1])] and tiles[1].path=="original-image")
 assert(tiles[2].title==roomTranslations[optionKey(originals[2])] and tiles[2].path=="alternate-image")
 assert(not tiles[1].active and tiles[2].active and choices[1]==0 and choices[2]==1)
 assert(tiles[1].y>=labels[1].y+labels[1].height)
 assert(labels[2].y>=tiles[2].y+tiles[2].height and bottom>=tiles[4].y+tiles[4].height)
 assert(tiles[1].height>=stockFontHeight+40)
 w.addLabel,w.addTile,w.selected,w.actions.choice=addLabel,addTile,selected,choice
 GFL2CharacterRuntime.selectedOption=selectedOption
 getTextManager=previousMetrics
end
GFL2CharacterRuntime.groupCount,GFL2CharacterRuntime.groupLabel,GFL2CharacterRuntime.optionCount,GFL2CharacterRuntime.optionLabel=
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
w=GFL2RoomWindow.open(actor,actions)
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
 wallet={targeted=0,recruitment={owned={GFL2Soppo=true}}}})
assert(w.drawAnimation.won and economy.balance(actor,"targeted")==0)
w.drawAnimation=nil;w:refresh()
assert(w:selectSkin(w.byCharacter[newcomer.name].baseSkin))
assert(#commands==3 and commands[3].command=="Wear" and equipped==0)
server("Transaction",{request=2,result={status="ok"},wallet={recruitment={owned={GFL2Soppo=true}}}})
assert(equipped==1)
w:close()
return "PASS Workshop links, recruitment/equip and the hidden VMD entry"
