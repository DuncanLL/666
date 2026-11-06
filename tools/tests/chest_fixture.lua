function UI:instantiate()if not self.instantiated then self.instantiated=true;if self.createChildren then self:createChildren()end end end
function UI:getIsVisible()return self.visible end
function UI:drawTextRight()end
function getText(key)return key end
function UI:drawTextureScaled()end
function UI:drawTextureScaledAspect()end
function UI:drawRect()end
function UI:drawRectBorder()end
function UI:drawText()end
function recordDraw()end
local api=AimohPMXPreview
local default={45,12,50,100}
calls.chest=0;calls.voice=0;calls.physicsReset=0
AimohPMXVoiceEngine={modelChanged=function()calls.voice=calls.voice+1 end}
function api.hasChestPhysics(a)return a.current>=0 and registryModels[a.current+1].chest end
function api.chestPhysicsSlider(a,i)return calls.chestValues and calls.chestValues[i+1]or default[i+1]end
function api.chestPhysicsStatus()return ""end
function api.setChestPhysicsSlider(a,i,value)
 assert(i>=0 and i<4 and value>=0 and value<=100);calls.chest=calls.chest+1
 calls.chestValues=calls.chestValues or {45,12,50,100};calls.chestValues[i+1]=value;a.settings="chest-settings-"..i.."-"..value;return ""
end
function api.resetChestPhysicsSettings(a)calls.chestValues={45,12,50,100};a.settings="chest-defaults";return ""end
function api.resetModelPhysics()calls.physicsReset=calls.physicsReset+1;return ""end
