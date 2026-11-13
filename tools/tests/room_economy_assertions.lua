local E=Economy
local pools={skinA={},skinB={},skinC={}}
local player={data={R116ReadyRoomWallet={
    credits=12345,targeted=17,outfit=22,
    recruitment={owned={Daiyan=true},misses=57},
    outfits={skinA=true,skinB=99,normal=true},
    daiyanUnlocked=true,daiyanMisses=50,
}}}
function player:getModData()return self.data end
local wallet=player.data.R116ReadyRoomWallet
E.resetTestDraws(player,pools)
assert(E.outfitGuarantee==100 and E.characterGuarantee==80)
assert(not E.characterUnlocked(player,"Daiyan") and E.characterMisses(player)==0)
assert(not E.outfitOwned(player,"skinA") and E.outfitMisses(player,"skinB")==0)
assert(E.outfitOwned(player,"normal"))
assert(E.balance(player,"credits")==0 and E.balance(player,"targeted")==0 and E.balance(player,"outfit")==0)
assert(wallet.drawResetBackup20261113.credits==12345)
assert(wallet.drawResetBackup20261113.recruitment.misses==57)

wallet.recruitment.owned.Daiyan=true
wallet.recruitment.misses=57
wallet.outfits.skinB=99
wallet.outfit=110
wallet.targeted=23
wallet.credits=1199
local restored={data=player.data,getModData=player.getModData}
E.resetTestDraws(restored,pools)
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
return "PASS one-time reset, retained purchases, all price tiers and balances, independent outfit pity and shared character pity"
