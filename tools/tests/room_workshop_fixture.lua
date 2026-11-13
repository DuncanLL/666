local oldRequire=require
function require(name)
 if name=="AimohPMXRoomEconomy"then return economy end
 return oldRequire(name)
end
function getTimestampMs()return 10000 end
function UI:getIsVisible()return self.visible end
function UI:setTooltip(s)self.tooltip=s end
function UI:setScrollWithParent()end
function ISScrollingListBox:ensureVisible()end
function UI:drawTextCentre()end
function UI:drawTextRight()end
function recordDraw()end
function getTexture(path)return {path=path,getWidth=function()return 340 end,getHeight=function()return 156 end}end
ISSliderPanel=UI:derive("Slider")
function ISSliderPanel:instantiate()self.sliderBarDim={}end
function ISSliderPanel:setDoButtons()end
function ISSliderPanel:setValues()end
function ISSliderPanel:paginate()end
opened={}
overlayEnabled=false
function isSteamOverlayEnabled()return overlayEnabled end
function activateSteamOverlayToWorkshopItem(id)table.insert(opened,{kind="overlay",id=id})end
function openUrl(url)table.insert(opened,{kind="browser",url=url})end
