# Shoot to Thrill 排查与 0.3.8 工坊准备（2026-11-08）

## 责任与处置

用户报告一、三人称转身时枪与瞄具分离，换回原版角色仍有现象。本轮只读检查生产实现，不修改 VP、Shoot to Thrill、人物运行代码或已安装模组；没有操作游戏或上传工坊。

**目前没有确认由 R116 引起，不做兼容补丁；也不能据组件检查宣称反馈现场已经查明或修复。**

- 实际本地模组为 Shoot to Thrill 3.6.72，工坊 3813032735；`common/mod.info` 明确排斥 ProjectViewpointADS、ViewpointTrueBallistics、旧 ISeeThem 和另一单/多人版本。安装存在不代表当前启用。
- 现有 Steam 日志为 11 月 8 日 16:32 的启动记录，86 个模组含 ModernFirearmsSystem，不含 ShootToThrill / ISeeThem。F 日志也无该模块记录。因此没有覆盖用户本次分离现场的日志，不能凭旧 ADS 报错认定新故障。
- R116 的 `Main.disable` 清除待切换、准备中、活动会话和骨骼守护所有权；`Bridge.end` 没有会话就退出。换回原角色后，这条持物位置重映射不运行。模块的通用着色支持仍加载，不能把“恢复原角色”等同完全卸载 R116。
- 使用 PMX 时，`DrawAccess.collect` 递归保留整个持物树；本体及子配件经过同一个公共映射，`ownPlacement` 都跟随 PMX 主体，不是只移动枪而遗漏瞄具。

## 找到的公共跟随风险及证据限度

检查实际 VP 0.1.7-rc2 与 Shoot to Thrill 3.6.72：VP 的 `ModelCapture.hang` 为直接挂手骨的枪建立骨骼跟随；静态枪下的静态瞄具不走同一骨骼挂接。`ModelBlend.rides` 对两类绘制使用不同路径。Shoot to Thrill 又在 `ModelPass.prepare` 前临时修改持物矩阵，并由 `LocalMotion.stationary` 改写历史矩阵。

在私有诊断中，使用原 JAR 的真实 `ModelDraws`、`ModelBlend.rides` 和 `LocalMotion.stationary`，构造一个捕获手骨 30°、显示手骨 15°的帧间差：

- 不加载 R116，隔离跟随阶段可出现相对挂点误差约 0.188 模型单位。
- 仅诊断性地让两者共享跟随父级，误差约 6.14e-8。
- 调用实际 R116 `DrawAccess.ownPlacement` 的公共父级处理，误差同样约 6.14e-8。

这是**特定跟随阶段的受控样本**，不是完整 STT 输入、捕获、相机与渲染循环；没有验证后续 ArmsTurn、实际枪模与现场设置，因此只证明外部公共链能独立产生此类相对差，不能认定反馈现场唯一根因。私有反编译资料和检查均在被忽略的 `build/stt-investigation`，不提交第三方代码，不随包发布。没有为诊断修改外部 JAR。

若继续定位，只需用户正常遇到时的当前启动日志、枪与瞄具名称及短录屏；不要求逐人物重测。按用户边界，仅在确认 R116 有责任时修自身公共链路。

## 本次工坊准备

- 仍为 R116 **0.3.8**，源码 `aa992a0`；本轮不改运行行为、不新增版本。
- 复核当前独立候选与源清单完全一致。Steam 和 F 的 `Workshop/R116/Contents/mods/AimohPmxPreview` 均为 1,993 文件、1,319,463,386 字节，逐文件哈希与候选相同，无需重复覆盖。
- 人物 JAR：8,262,173 字节，SHA256 `104053d1b2cae463a283c2a9c5576d0e211d11a6c4f72d0f226dab94f78c1178`。
- 保留人物工坊 ID **3834454638**、名称 R116、不可见空白简介、纯色预览，以及各作者项目原有标签和可见性。
- 游戏自带工坊目录校验通过；11 份模型原生初始化与采样组件通过，退出后所有原生对象计数归零。未启动游戏，不新增真实游玩或订阅冷启动通过声明。
- 包内保留人物、贴图、配件、语音、现用新胸部物理与既有渲染输入资料；不包含 VP、STT、工作台或独立光影分发 MOD。不合入另一对话进行中的渲染代码。
- 上传入口仍为 Steam 用户目录里的 `Workshop/R116`；本轮只准备，**未点击上传工坊**。完整项目约 1.32 GB（1.23 GiB），没有另复制一份大包。

当前证据：`build/stt-investigation/{carry-check.log,workshop-comparison.json}`、`build/workshop.json`、`build/runtime-check.json`。前者是责任排查，后两项是包体和原生组件检查，均不代表真实游戏故障已经解决。
