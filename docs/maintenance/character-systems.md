# 人物数据、整备室、联机与语音

## 一套衣装的三份资料

| 层 | 入口 | 定位失败时先查 |
|---|---|---|
| 游戏人物 | 核心 [registry.json](../../game/mod/42/media/aimoh_pmx/registry.json) 和已启用包的 `media/aimoh_pmx/packs/<包键>.json`。每个模型条目指向 PMX 与 profile。 | 核心的 `packs` 是许可模型集合，`pack_mod_ids` 将历史包键映射到实际启用的 MOD ID。查角色包是否启用、路径是不是实际解析到的文件、配置指纹是否和 PMX 对得上。 |
| 整备室 | 核心 [catalog.json](../../game/mod/42/media/ui/AimohPMXReadyRoom/catalog.json) 与角色包的同类 UI 分片，由 [ReadyRoomPreview.data](../../game/java/aimoh/pmxpreview/ReadyRoomPreview.java) 合并给 Lua。 | 角色能选但缺头像、衣装名或描述，先查 UI 分片、模型 ID 和路径。准备下一次维护时按窗口生命周期重读，避免引用旧 Lua 会话的表。 |
| 原游材质 | `media/gfl2_rendering/characters.json` 及角色包的 `gfl2_rendering/packs`，加上模型的着色 sidecar。 | 普通视角有模型、专属光影缺件时，查 PMX SHA、材质槽和顶点数量、源 renderer 对应和纹理资源。见 [光影章](rendering.md#一套人物怎样进入两种光影)。 |

`pmx-...` 是动作会话和存档中选择衣装的稳定 ID。profile 中的 `pmx_sha256` 表示实际模型版本。旧文件命名中的包键和角色键可能被多个索引用到。改工坊标题、文件夹或显示名时，先区分实际 MOD ID、模型 ID、外部文件名和存储键。模型、语音与原游着色文件使用 [外置清单](../../resources/manifest.json) 读回。

## 整备室页面和存档

[窗口控制](../../game/mod/42/media/lua/client/AimohPMXWindow.lua) 负责页面、预览请求和动作按钮。[游戏 Lua 入口](../../game/mod/42/media/lua/client/AimohPMXPreview.lua) 负责当前玩家的请求、持久化、切换完成后通知和恢复。预览由 [ReadyRoomPreview](../../game/java/aimoh/pmxpreview/ReadyRoomPreview.java) 持有独立会话，浏览其它人物或衣装只更新预览。确认穿戴进入 [模型切换](gameplay.md#从游戏角色到模型会话)。

| 玩家操作 | 实际数据与状态 |
|---|---|
| 浏览、招募、穿戴 | 框架资料提供人物与外观目录。未加载的角色由工坊入口引导。已有角色可预览其他衣装。抽取或购买只改变归属，确认穿戴才切换游戏会话。 |
| 配件、显隐和色彩 | [Wardrobe](../../game/java/aimoh/motion/Wardrobe.java) 与 [ModelAppearance](../../game/java/aimoh/motion/ModelAppearance.java) 解析 `appearance_controls` 组、选项和规则。菜单提交稳定的组和选项 ID。规则应用到该造型真实的材质槽和 morph。 |
| 手动表情 | 玩家预设与正在穿戴的造型分开记。表情输入按现用模型的表情名称和权重核对。联机整备室会合并滑条修改，角色切换前把仍在等待的修改提交一次。 |
| 读档、恢复和换装 | [Main.settingsFor](../../game/java/aimoh/pmxpreview/Main.java) 将保存的外观同当前 profile 整合。有 `previous_material_slots` 且旧数组长度吻合时才迁移旧显隐、颜色、透明度、贴图和双面标记。当前衣装的规则表重新从 profile 绑定。新槽位补用当前默认值。 |
| 物理按钮 | 界面根据当前模型实际能力显示。保存的是身体物理总开关及相应的局部开关。更换造型不会凭旧的菜单项生成该 PMX 缺少的软体资料。 |
| VMD | 默认隐藏。0.7.15 在整备室本机资源区长按「定向许可」约 10 秒切换入口，作用在当前玩家会话。松手、移开和出现其它条件会取消长按，触发成功后消耗这次点击。 |

动作读取优先走预览页提供的库入口。自定义文件库是游戏缓存目录中的 `GFL2Exilium/Motions`，内置动作在核心 `media/aimoh_pmx/motions`。选择额外目录时，播放检查是否属于登记的文件或目录，校验文件类型和文件归属。动作命令有 `play`、`pause`、`speed`、`seek`、`restart`、`stop`、`physics` 和 `reset`。进度条带动作修订号，拖到新位置会重置物理历史，旧的拖动不会作用到另一段动作。速度范围为 0 到 1。暂停时动作与物理的推进时间为零，设为 0 倍速时只停动画时间。播放时把动画交给 VMD 层并启用该层需要的腿部 IK 与 morph。停止时释放动作句柄、恢复 PZ 姿态路径、恢复之前的常规物理开关。[VMD 解析](../../game/java/aimoh/motion/VmdInput.java) · [控制层](../../game/java/aimoh/motion/PmxModel.java) · [0.7.15 入口记录](../../provenance/releases/0.7.15-vmd-entry-toggle.json)

## 资源、抽取和专武各自怎么结算

共享的 [AimohPMXRoomEconomy](../../game/mod/42/media/lua/shared/AimohPMXRoomEconomy.lua) 服务于单机和服务端。余额、归属和保底与角色模型资源分开保存。以前发过的存档键，包括 `R116ReadyRoomWallet`，属于持久化契约。单机的钱包与表情等设置有本地恢复入口，服务端联机钱包则按账号和当前世界保存。

| 规则 | 该看哪部分 |
|---|---|
| 兑换和抽取 | `buy` 管兑换，`draw` 是共同抽取器。本期显示与成本由共享逻辑核对。抽取只接受单次或十次，最多走到保底，扣除实际执行次数。 |
| 保底与归属 | 角色保底 80，专武保底 70，抽取衣装保底 100。角色和专武各自共享本类进度，衣装按衣装 ID 保存进度。旧戴烟解锁及旧专武进度在共同读取函数兼容。改卷率、门槛或保底时同时核对单机和联机。 |
| 衣装购买 | 部分衣装用余额购买，其余走抽取。服务器的材质外观请求和穿戴许可共同校验 `GFL2MultiplayerCatalog`，因此界面可见、已订阅和有穿戴权限是三个独立条件。 |
| 领取专武、弹匣、涂装 | [GFL2SignatureWeapon](../../game/mod/42/media/lua/shared/GFL2SignatureWeapon.lua) 与 [GFL2SignatureMagazines](../../game/mod/42/media/lua/shared/GFL2SignatureMagazines.lua) 定义实际物品和弹匣。共享脚本写物品，服务端在联机时处理交易。[GFL2WeaponSkins](../../game/mod/42/media/lua/shared/GFL2WeaponSkins.lua) 维护枪皮身份。 |
| 读档兼容和平衡 | [SignatureWeaponRangeMigration](../../game/java/aimoh/pmxpreview/SignatureWeaponRangeMigration.java) 在游戏装载武器时识别旧值，保留配件带来的变化。[CovenantWeaponTextures](../../game/java/aimoh/pmxpreview/CovenantWeaponTextures.java) 给指定枪皮图集保留原分辨率。[GFL2AutomaticCadence](../../game/mod/42/media/lua/shared/GFL2AutomaticCadence.lua) 仅在 SWMG 启用时注册游戏内自动射速，并在相应的 RealAuto 路径处理额外反冲延迟。 |

专武摆放由[游戏中的持物链](gameplay.md#持物位置为什么要分捕获和插值)负责。交易状态和画面里的枪模型有各自入口，出现“枪在背包里，画面外形不对”时分别核对物品类型、涂装 ID、模型引用和绘制矩阵。

## 联机握手、外观发布与回退

协议入口是 [GFL2MultiplayerProtocol](../../game/mod/42/media/lua/shared/GFL2MultiplayerProtocol.lua)，模块名 `GFL2Exilium`，版本 1。[客户端](../../game/mod/42/media/lua/client/GFL2MultiplayerClient.lua) 和 [服务端](../../game/mod/42/media/lua/server/AimohPMXNetwork.lua) 分担以下步骤。

1. 本机客户端用 `Request` 建握手。服务器从游戏提供的玩家对象、在线 ID 和用户名确定账号与会话，将账本放在该世界的 `GFL2ExiliumMultiplayerV1`。回复 `Wallet` 和 `Snapshot`，前者提供钱包、事务序号和已穿戴外观，后者包含其他会话的当前外观。客户端将回执绑定到发起握手的玩家对象。
2. 单一账号的客户端同时保持一笔待确认交易。每 3 秒可按原请求序号重传。服务器检查下一序号，对最近的相同事务回放结果，物品和扣款只提交一次。会话 token 和角色对象一起防止复用联机槽位时串状态。单机在客户端模块入口返回，纯单机的钱包直接走共享逻辑。
3. 客户端先拿穿戴许可，再加载模型。画面就绪后，`Appearance` 广播模型 ID、由 64 个十六进制字符表示的 SHA-256 指纹及最多 8 KiB 的外观数据。服务器再次检查包是否启用、角色和衣装归属。迟到的拒绝通过 attempt 序号区分，当前请求失败时恢复服务器仍认可的造型。
4. 好友客户端按自己的已安装包解析外观。Java 验证模型和指纹，对照实际材质槽检查显隐、配件选择与表情。远端人物沿 PZ 动作绘制，次级物理关闭，光影由观看端决定。物品显示使用游戏本身的装备状态。
5. 死亡时服务器清当前外观广播和会话，账号进度留在世界账本。客户端断线、退出菜单、重连和新生还者都会更新会话状态。本机角色的配置、外观和语音恢复各经过自己的就绪检查。

服务器名单由 [生成脚本](../../tools/generate_multiplayer_catalog.py) 从框架与角色包资料提取。某个角色本机可穿、服务器拒绝时，优先检查服务器启用的 MOD、生成名单里的模型 ID、账号归属和回执序号。已有 [多人验收矩阵](../multiplayer-framework.md) 和 [真实 UDP 回环记录](../../provenance/releases/multiplayer-loopback-validation.json)，其中的受控客户端和完整双客户端实机要分别理解。

## 语音如何跟游戏状态走

入口是 [VoiceEngine](../../game/mod/42/media/lua/client/AimohPMXVoice/VoiceEngine.lua)。[CatalogVoice](../../game/mod/42/media/lua/client/AimohPMXVoice/CatalogVoice.lua) 与角色包提供语音 profile、cue、音频和字幕。语音归第一个本机听者，在本地角色真正就绪并判定为当前造型后激活。观察端的联机人物画面使用外观协议，角色音频通过本机语音链处理。

| 游戏状态 | 路由 |
|---|---|
| 普通呼喊、蹲姿喊声 | 先让游戏按键、轮盘或手柄的原有喊叫入口执行。语音模块确认游戏发出了相应 vocal 后，按是否蹲姿选角色 cue 并处理原生声音。游戏拦截了呼喊时，角色模块也保持该门控。 |
| 饥饿、口渴、乏力、疼痛、流血等 | [FullVoiceCore](../../game/mod/42/media/lua/shared/AimohPMXVoice/FullVoiceCore.lua) 记录 Moodle 级别、cue 与优先级。[VoiceCore](../../game/mod/42/media/lua/shared/AimohPMXVoice/VoiceCore.lua) 和 profile 的候选条件、间隔、状态变化共同决定何时播放。加速时限制普通优先级候选。 |
| 击杀和受伤 | 击杀源自 `OnZombieDead` 的攻击者，检查是否当前角色、存活、清醒且配有触发 cue。受伤从游戏伤害事件进入。角色的音频池可不同，判断委托给公共规则。 |
| 睡觉与醒来 | 入睡停止当前角色声音，醒来重新初始化采样。暂停和游戏速率变化由各入口的时钟及状态门控处理。 |
| 存档、换人、死亡与退出 | [NativeVoice](../../game/mod/42/media/lua/shared/AimohPMXVoice/NativeVoice.lua) 与 [VoiceRouting](../../game/mod/42/media/lua/shared/AimohPMXVoice/VoiceRouting.lua) 记录和恢复原 vocal 前缀。停止时清有限会话句柄与字幕。存档前后切回并恢复运行时前缀，兼容其他人物库的前缀还原链。 |

本地化阶段继续按现有翻译键、字幕 cue 与槽位维护。每次合并文本都核对角色名称、衣装、菜单和语音键的覆盖，保留旧键别名的读取行为。音频存在性、字幕命中和双端实际能听到的内容需要各自验证。
