local E=Economy
function isClient() return false end
function isServer() return false end
local globalData={}
ModData={getOrCreate=function(key)
    globalData[key]=globalData[key] or {}
    return globalData[key]
end}
local player={data={R116ReadyRoomWallet={
    credits=1199,targeted=23,outfit=110,
    outfits={skinB=99,normal=true},daiyanUnlocked=true,daiyanMisses=57,
}}}
function player:getModData() return self.data end
function player:getPlayerNum() return 0 end
local wallet=player.data.R116ReadyRoomWallet
assert(E.outfitGuarantee==100 and E.characterGuarantee==80)
assert(E.characterUnlocked(player,"Daiyan") and E.characterMisses(player)==57)
assert(E.outfitOwned(player,"normal") and E.outfitMisses(player,"skinB")==99)
local restored={data={},getModData=player.getModData,getPlayerNum=player.getPlayerNum}
assert(E.characterUnlocked(restored,"Daiyan") and E.characterMisses(restored)==57)
assert(E.balance(restored,"credits")==1199 and E.balance(restored,"targeted")==23)
assert(E.buyOutfit(restored,"premium",1200)=="short" and E.balance(restored,"credits")==1199)
assert(E.buyOutfit(restored,"regular",600)=="unlocked" and E.balance(restored,"credits")==599)
assert(E.buyOutfit(restored,"regular",600)=="unavailable" and E.balance(restored,"credits")==599)
assert(E.buyOutfit(restored,"free",0)=="unavailable" and E.balance(restored,"credits")==599)
wallet.credits=840
assert(E.buyOutfit(restored,"themed",840)=="unlocked" and E.balance(restored,"credits")==0)
wallet.credits=1200
assert(E.buyOutfit(restored,"premium",1200)=="unlocked" and E.balance(restored,"credits")==0)

local high=function()return 9999 end
local status,results=E.drawOutfit(restored,"skinB",10,high)
assert(status=="unlocked" and #results==1 and E.balance(player,"outfit")==109)
assert(E.characterMisses(player)==57)
for n=1,9 do assert(E.drawOutfit(player,"skinC",10,high)=="miss") end
assert(E.drawOutfit(player,"skinC",10,high)=="unlocked" and E.outfitOwned(player,"skinC"))
assert(E.balance(player,"outfit")==9)
assert(E.drawOutfit(player,"skinC",1,high)=="unavailable")
assert(E.drawOutfit(player,"skinA",10,high)=="short" and E.balance(player,"outfit")==9)
assert(E.drawCharacter(player,"Other",10,high)=="miss")
assert(E.drawCharacter(player,"Other",10,high)=="miss")
status,results=E.drawCharacter(player,"Other",10,high)
assert(status=="unlocked" and #results==3)
assert(E.characterMisses(player)==0 and E.balance(player,"targeted")==0)
local blade={id="GFL2.CauseAndEffect",character="Phaetusa"}
local rifle={id="GFL2.TestRifle",character="Other"}
wallet.recruitment.owned.Phaetusa=true
wallet.recruitment.misses=37
wallet.targeted=12
wallet.signatureWeapon={misses=59,featured=true,acquired=true,claimed=true}
assert(E.weaponMisses(player)==59)
local other={id="GFL2.OtherBlade",character="Phaetusa"}
status,results=E.drawWeapon(player,other,10,high)
assert(status=="miss" and #results==10 and E.weaponMisses(player)==69)
status,results=E.drawWeapon(player,rifle,10,function() error("The 70th pull must not roll") end)
assert(status=="unlocked" and #results==1 and E.weaponMisses(player)==0)
assert(wallet.targeted==1 and E.characterMisses(player)==37)
assert(E.weaponProgress(player,rifle.id).acquired and E.weaponProgress(player,blade.id).claimed)
local carried={}
local added=0
local inventory={}
local function item()
    local data={}
    return {getModData=function() return data end}
end
function inventory:containsTypeRecurse(id) return carried[id]==true end
function inventory:AddItem(id)
    added=added+1
    carried[id]=true
    return item()
end
function player:getInventory() return inventory end
assert(E.claimWeapon(player,rifle)=="ok" and added==1)
assert(E.claimWeapon(player,rifle)=="alreadyCarried" and added==1)
carried[rifle.id]=nil
assert(E.claimWeapon(player,rifle)=="ok" and added==2)
local respawn={data={}}
function respawn:getPlayerNum() return 1 end
function respawn:getModData() return self.data end
respawn.data.R116ReadyRoomWallet={signatureWeapons={[blade.id]={acquired=true}}}
local replacement
function respawn:getInventory()
    return {containsTypeRecurse=function(_,id)return replacement==id end,
            AddItem=function(_,id)replacement=id;return item() end}
end
assert(E.weaponProgress(respawn,blade.id).acquired and not E.characterUnlocked(respawn,"Phaetusa"))
assert(E.claimWeapon(respawn,blade)=="ok" and replacement==blade.id)
assert(E.claimWeapon(respawn,blade)=="alreadyCarried")
assert(E.claimWeapon(respawn,other)=="unavailable")
assert(E.balance(respawn,"targeted")==0 and wallet.targeted==1)
assert(E.grantTestCredits(player) and E.balance(player,"credits")==50000)
local previousClient=isClient
isClient=function() return true end
assert(not E.grantTestCredits(player) and wallet.credits==50000)
isClient=previousClient

return "PASS wallet, shared character and weapon pity, outfit pity, legacy unlock migration, loss and death recovery, and single-player credits"
