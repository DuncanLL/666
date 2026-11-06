# 0.3.0 胸部物理试装

用户已认可 V3 工作台视觉并要求游戏试装；游戏实际游玩与最终提交发布仍待用户确认。四项参数位于整备室独立“胸部物理”栏，使用既有按人物保存的配置。没有加入身体、手或衣物接触、额外 IK、预热或逐角色动作补丁。

## 公共接入

- C ABI 与体积求解的实际源在 `native/chest-softbody/softbody.cpp`。复用现有 JDK FFM 接法，不加新的 JNI 工具链或物理库。
- 模型资料在构建时由 `tools/chest_model_data.py` 从原始 PMX 权重生成，打包于 JAR，按实际 PMX SHA 查找。游戏不解析第二遍 PMX，不复制修改整人模型，也不改其 SHA、UV、材质或光影资料。
- 新增通用原生 API 将已声明的胸骨物理转为动画所有，保留其碰撞体及其它头发/服装刚体，防止新旧解算叠加。源码为自有 Rust 接入与已有 Bullet 包装器。
- 最终动作/握持完成后只推进一次新解算。绘制与整备室读取同一不可变快照，不另走模拟，也不改当前动作展示插值。
- 手动“重置物理解算”同时覆盖两种物理；异常时只暂停本组件并保留原网格，避免整个人物失效。没有自动反复重置。
- 滑块只更新已有参数；重量同时更新 Bullet 逆质量缓存。保存走既有 profile 存档，不重建人物或重置语音。

本次生成了六套有效绑定资料：芙玲、春田、阿斯缇亚 JK、克莱妲、OTs-14、妮基塔。妮基塔原有隐藏入口保持；没有有效绑定的模型维持原行为。

## 构建与检查

工具链/依赖只读 `.local/paths.json`，包括已有 PMX reader 的脚本目录；工作台源码不是游戏构建依赖。二进制及模型不入 Git。

```text
python tools/build_chest_trial.py
python tools/check_chest_runtime.py --poses <本机真实动作采样.npz>
python tools/check_chest_parity.py --poses <同一采样.npz>
python tools/check_chest_menu.py
python tools/check_runtime.py build/workshop/chest-trial/R116
```

游戏 Java/C ABI 同输入的完整顶点与法线对照误差为零；11 套模型加载、六套连续动作、参数恢复、物理关闭/重置、零残留世界均有本地检查记录。Lua 检查使用真实 Kahlua 和游戏滑块代码，角色/控件由替身提供；不能当作游戏 UI 实测。真实游玩、帧数、多人、订阅冷启动及历史切换崩溃不在这些通过结论内。

继承的体积误差和强人工冲击回形残差没有在本轮宣称修复。当前默认值保持已认可工作台方案，实际性能需用户在游戏中确认。

## 安装与发布边界

`tools/install_chest_trial.py` 只更新显式配置的游戏 MOD 目录里的 JAR、两份 Lua、版本、增量菜单文字和对应小图片。模型、贴图、语音、外部光影及工坊元数据不替换；回退只保存被改的小文件。跨盘暂存位于目标同卷，读回校验，失败回退。

当前提交仅本地保存；未获得游戏实测认可前不推送、不上传工坊。安装回读与候选位置在被忽略的 `build/game-softbody/install.json`、`build/workshop.json`。
