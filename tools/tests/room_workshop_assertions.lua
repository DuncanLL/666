local actions={}
local w=AimohPMXWindow.open(actor,actions)
assert(#w.characters==15)
for _,character in ipairs(w.characters)do
 w:onCharacter(character)
 assert(character.locked and w.workshopButton.visible)
 local id=roomData.roomWorkshopItems[character.name]
 assert(id)
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
 w:requestWorkshopPage()
 assert(not w.workshopPrompt)
end
w:close()
return "PASS all 15 missing modules: first click, cancel, back, confirmed browser/overlay destination, stale clicks, modal hit blocking; loaded modules hide action"
