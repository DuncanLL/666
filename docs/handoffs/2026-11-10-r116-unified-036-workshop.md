# R116 0.3.36 人物与光影单 MOD

工坊项目为 `3834454638`，游戏 MOD ID 为 `AimohPmxPreview`。合包使用人物 0.3.35（`d2f1c57`）与封装光影 0.3.25（`c26d6f4`）。运行宿主为 Steam 正式 Viewpoint 0.1.6.2。启用 R116 后，人物入口会在首帧前注册 Aimoh-Native-GF2 和 Aimoh-Eclipse-GF2。

用 `tools/package_r116_unified.py` 组装，并用 `tools/check_r116_unified.py` 检查。对 2,218 个工坊文件全量比对，更新限于版本号和 JAR。旧独立分发的两种加载顺序均触发防重复挂接。正常的单 MOD 启动通过游戏工坊校验，原有光影选项和自定义包通过保留测试。

AMD RX 9070 XT 上从合包的 ZombieBuddy 入口注册两套光影，分别用同一个 JAR 绘制克莱妲。Native 验证人物专用材质、阴影和静态运动向量，Eclipse 开启 TAA 并验证专用材质。两套离屏场景的 OpenGL 错误数均为零。真实进档和跨显卡验收仍待进行。

本机游戏实际使用的作者目录、本地 MOD 目录和 Steam 本机缓存已回读同一合包。旧独立光影从本机三处加载路径移出并留存备份。安装及回退记录位于 `D:/CodexBuild/R116Render0162/unified-036-install.json`。
