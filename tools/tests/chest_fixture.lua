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
local api=GFL2CharacterRuntime
calls.voice=0;calls.physicsReset=0
GFL2VoiceEngine={modelChanged=function()calls.voice=calls.voice+1 end}
function api.hasChestPhysics(a)return a.current>=0 and registryModels[a.current+1].chest or false end
function api.chestPhysicsEnabled(a)return api.hasChestPhysics(a) and calls.chestOn~=false end
function api.setChestPhysicsEnabled(a,on)
 assert(api.hasChestPhysics(a))
 calls.chestOn=on;a.settings=on and "chest-on" or "chest-off";return ""
end
function api.resetModelPhysics()calls.physicsReset=calls.physicsReset+1;return ""end
