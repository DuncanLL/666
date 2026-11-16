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
calls.voice=0;calls.physicsReset=0
AimohPMXVoiceEngine={modelChanged=function()calls.voice=calls.voice+1 end}
function api.resetModelPhysics()calls.physicsReset=calls.physicsReset+1;return ""end
