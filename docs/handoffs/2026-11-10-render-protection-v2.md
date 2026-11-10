# R116 0.3.23 人物光影封装试读第二版

供只拿到发行 JAR 的试读者检查。普通构建仍使用可读着色源码，封装构建继承第一版 Java 和 GLSL 的符号处理。两者共用 PZ 42.22 与已核验的官方 Viewpoint 0.1.7-rc4。

## 边界

- Native 封装八个自写着色文件。Eclipse 封装自写天空光/反射适配、三份自写计算阶段，以及合成阶段里的自写人物运动片段。两套参数表的完整内容进入同一份封装数据。玩家目录的包只保留必要的发现信息、菜单、占位符和其余既有资源。
- 普通版无需封装数据。试读版经正常 ZombieBuddy 入口接入两条现有读取路径，第一次读取占位符时在内存解开 AES-GCM 数据。Iris 继续使用它自己的文本缓存，渲染代码继续使用原有编译产物。重载时从内存取片段，GPU 不做封装解码。
- 安装器使用与 JAR 相同的占位资源。Native 更新已有作者托管目录时沿用原有回退备份规则。旧版留下的历史副本可能包含旧明文。构建检查在空玩家目录进行。
- 修改范围按 release-protection.json 的清单和仓库自写运动片段确定。原 VP JAR、签名以及清单之外的包条目逐字节核对。Java 内嵌的小段 GLSL 与尚未划清归属的历史混合源码继续沿第一版边界处理。
- 这是发行包拆读门槛。密钥和读取逻辑在客户端，运行时可以取得编译前源码。判定范围是从 JAR 和它正常安装到空目录后的文件中直接解包阅读。

## 重建和验证

先按普通流程构建 Native、Eclipse、合包，再执行以下命令。分发给试读者时只给 Workshop 目录内的 AimohGF2Eclipse.jar。可读源码在 Git，映射和构建输入仅在 build 输出的 private/。

~~~powershell
python tools/protect_render_release.py --proguard-zip <官方ProGuard压缩包> --metadata-from <光影工坊目录> --output build/protected-r116-0.3.23-v2
python tools/check_render_support.py --project build/protected-r116-0.3.23-v2/Workshop/R116RenderSupport --protected-report build/protected-r116-0.3.23-v2/protection-report.json
~~~

启动检查验证真实 ZombieBuddy 调度、官方 VP 两套包的发现与设置、安装文件仍封装、Eclipse 内存计算阶段恢复原 SHA-256、Native 能恢复参数和光照代码，以及官方游戏工坊目录校验器。单测直接引用的内部符号在普通构建检查，试读 JAR 通过实际入口和 AMD 组件绘制检查。

## 本轮验证

- 第一版能直接读到的 Native 光照实现注释、相机补光参数值、证据指纹、Eclipse SH 输入适配注释，从新 JAR 及内嵌 ZIP 递归检查均无匹配。封装清单之外的条目与第一版一致。
- 同机器各七次新 JVM 对照。Eclipse 官方发现和配置检查的中位数为 175.37 → 173.09 ms，Native 发现和读取检查为 42.20 → 43.50 ms。这些是组件加载数字，实际进存档耗时另计。
- AMD RX 9070 XT 的 Native 跑过 36 帧换人物与 48 组人物程序链接。Eclipse 开 TAA 的 8 帧和 64 帧对照图与第一版一致。两条路径 GL 错误均为零。四个 Native 计算阶段与 66 份 Eclipse 展开阶段与第一版字节一致，覆盖已有参考编译链接通过的 33 组输入。
- 稳定帧计时用同一受控场景。Native 两次第一版中位数为 12.38 与 12.19 ms，第二版为 11.98 ms。Native 输出第一版重复跑相差 17 像素，第二版与两次第一版分别相差 16 和 8 像素。Eclipse TAA 64 帧去掉前 16 帧后，计时中位数为 9.68 → 8.48 ms。两套数字用于检查明显额外开销，完整游戏 FPS 另测。
- 状态 LIMITED_HARDWARE_VALIDATION。AMD 组件结果单独记录。NVIDIA、Intel、真实游戏存档、实际 Steam 订阅和外部 AI 盲读仍待验证。
