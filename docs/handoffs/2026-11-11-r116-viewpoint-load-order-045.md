# R116 0.3.45 Viewpoint 加载顺序修复

公司电脑的日志显示，ZombieBuddy 先调用 R116 的入口，随后才把 Viewpoint 的 JAR 加进类路径。0.3.44 在入口处读取 `viewpoint.render.ModelPass`，收到 `ClassNotFoundException` 后结束了光影初始化。人物模型后来可以正常加载，日志中的 Native 和 Eclipse 人物接入仍未完成。

0.3.45 保留宿主已可用时的原入口。遇到宿主尚未进入类路径的顺序时，由 `ViewpointStartupPatch` 在 ZombieBuddy 调用 Viewpoint 入口时重试。两套托管光影和早期回调先就绪。人物 `ModelPass` 的回调仍在原有角色补丁之后注册。

日志另外出现 RTX 3070 的 `gbuffers_terrain` 几何阶段输出预算错误 `C6033`。核对本机 0.3.44 和 0.3.45 的同名内嵌 Eclipse ZIP，它们只有地形顶点、细分控制、细分求值和片元四个阶段。公司电脑报错时具体读取的缓存文件未随日志提供。加载顺序夹具放入一份带旧几何阶段的托管缓存，0.3.44 复现启动异常并留下旧缓存，0.3.45 在首帧前完成升级。公司 RTX 3070 的实机结果待验证。

以安装版 0.3.44 JAR 为基线，只更新 `UnifiedStartup.class` 并加入 `ViewpointStartupPatch.class`。其余 JAR 条目逐字节核对，两套内嵌光影包和加密数据沿用基线。

| 验证 | 结果 |
| --- | --- |
| 官方 ZombieBuddy 分发 | 宿主已在类路径和先加载 R116 后追加宿主两种顺序均通过。两个包在首帧前可读。 |
| Eclipse | 官方 Viewpoint 展开后通过 29 项 OpenGL 参考链接，`gbuffers_terrain` 链接四阶段。 |
| 夏安离屏实绘 | RX 9070 XT 上 Native 和 Eclipse 各 5 帧，OpenGL 错误为零。对比 0.3.44 的末帧平均 RGB 绝对差分别为 0 和 1.144/255。 |
| 工坊 | 工坊目录通过游戏校验，测试保留已选光影和自定义包。 |

0.3.45 合包 JAR SHA-256 为 `6b1ee53e4af1908397689c2356a48c8e485d8a5e44ff391cc62ff64183dce75f`。本地构建证据在 `build/late-viewpoint-045/startup-check.json` 和 `build/late-viewpoint-045/install-check.json`。
