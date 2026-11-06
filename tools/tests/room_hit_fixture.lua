-- Only geometry/base widgets are substituted; ISButton mouse handlers come from the game.
function UI:isMouseOver()return false end
function UI:setTextureRGBA(r,g,b,a)self.textureColor={r=r,g=g,b=b,a=a}end
function UI:setBorderRGBA(r,g,b,a)self.borderColor={r=r,g=g,b=b,a=a}end
function UI:setBackgroundRGBA(r,g,b,a)self.backgroundColor={r=r,g=g,b=b,a=a}end
function getTextManager()return {MeasureStringX=function(s,f,t)return #t*8 end}end
function getSoundManager()return {playUISound=function()end}end
UITransition={new=function()return {}end}
local function inside(panel,x,y)
 return panel.visible and x>=panel.x and y>=panel.y and x<panel.x+panel.width and y<panel.y+panel.height
end
function hit(panel,x,y)
 for i=#panel.children,1,-1 do
  local child=panel.children[i]
  if inside(child,x,y)then return hit(child,x-child.x,y-child.y)or child end
 end
 return nil
end
function click(panel,button)
 local target=hit(panel,button.x+button.width/2,button.y+button.height/2)
 assert(target==button,(button.roomTitle or 'button')..' blocked by '..(target==panel.preview and 'preview' or 'another control'))
 button:onMouseDown(button.width/2,button.height/2);button:onMouseUp(button.width/2,button.height/2)
end
