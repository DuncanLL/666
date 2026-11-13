local weapons = {{
    id = "GFL2.CauseAndEffect",
    mod = "GFL2Phaetusa",
    character = "R116Phaetusa",
    name = "ContextMenu_GFL2_CauseAndEffect",
    description = "ContextMenu_GFL2_WeaponDescription",
    icon = "media/textures/Item_GFL2CauseAndEffect.png",
    card = "media/ui/GFL2/CauseAndEffectCard.png",
    previewCenterY = 0.264,
}, {
    id = "GFL2.Chernobog",
    mod = "GFL2Koleda",
    character = "Koleda",
    name = "ContextMenu_GFL2_Chernobog",
    description = "ContextMenu_GFL2_ChernobogDescription",
    icon = "media/textures/Item_GFL2Chernobog.png",
    card = "media/ui/GFL2/ChernobogCard.png",
    previewCenterY = 0.169,
    previewCenterZ = 0.0068,
    previewZoom = 15,
}, {
    id = "GFL2.AlvaAN94",
    mod = "GFL2Alva",
    character = "Alva",
    name = "ContextMenu_GFL2_AlvaAN94",
    description = "ContextMenu_GFL2_AlvaAN94Description",
    icon = "media/textures/Item_GFL2AlvaAN94.png",
    card = "media/ui/GFL2/AlvaAN94Card.png",
    previewCenterY = 0.171,
    previewCenterZ = 0.041,
    previewZoom = 15,
}}

local catalog = {byId = {}, byCharacter = {}}
for _, weapon in ipairs(weapons) do
    catalog.byId[weapon.id] = weapon
    catalog.byCharacter[weapon.character] = weapon
end
return catalog
