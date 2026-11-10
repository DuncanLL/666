# R116 0.3.23 光影保护试读版

试读版从已核对的 `0.3.23` 光影合包生成，目标是提高拆读自有渲染模块的成本。游戏运行时仍读取正常的 class 文件和 GLSL。普通构建、安装及人物与光影合包预检沿用各自入口。

## 保护边界

- Java 只处理可追溯的 R116 自有人物渲染代码。通过白名单改写内部类名、私有方法和私有字段。类仍在原 Java 包内，保留 ZombieBuddy 入口、VP 挂接、公开或包内调用及运行时反射需要的符号。ProGuard 只做改名，禁用收缩与优化。
- GLSL 只改 `render-support/native-pack/shaders/gf2` 的八个自写文件和 `render-support/eclipse/shaders/actor-motion.glsl` 进入合成阶段的自写片段。改名对象是审核过的局部标识符和文件内辅助函数。逐个验证替换可逆。混合 Eclipse 文件中其余字节、包内其他条目、第三方原始资源、官方 VP JAR 与签名及人物 JAR 保持原值。历史混合 GLSL 和 Java 中的运行时着色字符串未做额外处理。
- `release-protection.json` 保存可读的处理规则。日常修改继续用仓库里的原源码。每次保护构建的 JAR 指纹和规则指纹都写入报告，Java 与 GLSL 的对应表留在输出目录的 `private/`，与该次 JAR 配套用于诊断。

## 重建

普通的 Native、Eclipse 和合包构建完成后，可用以下命令生成独立试读包。ProGuard 使用官方 `7.10.0` ZIP，工具会核对规则中记录的发行指纹。参数 `--metadata-from` 指向现有光影工坊项目目录，用于继承项目 ID 和预览图。

```powershell
python tools/protect_render_release.py --proguard-zip <官方ProGuard压缩包> --metadata-from <光影工坊目录> --output build/protected-r116-0.3.23
```

试读 JAR 位于输出目录的 `Workshop/R116RenderSupport/Contents/mods/AimohGF2Eclipse/42/media/java/client/AimohGF2Eclipse.jar`。交给外部试读时单独提供该 JAR。普通构建继续作为可读对照，诊断时用对应报告核对版本，再查 `private/` 中该次构建的映射。

## 本次验证

- 输入 JAR SHA-256 为 `90d8a6437720bc66e487c1cabeda4f0e119b86239a005db3d28601f74aceef95`。输出为 `54e32c13eaf79eb8feb7faf83efab21ef862545ceceaadfcd1744627fd439e5a`，同输入重建两次完全一致。119 个自有 Java 类进入处理范围，47 个内部类名与 804 个成员名发生变化。
- ZombieBuddy 实际入口、人物补丁与 Native 回调顺序、两套光影发现和游戏工坊结构验证通过。AMD RX 9070 XT 上 Native 跑过 36 帧换人对照及 48 组人物程序实际链接，Eclipse 开 TAA 跑过 8 帧人物绘制。该 Eclipse 测试的受控输出与普通版逐像素一致。四组展开后的 Native 计算程序与 29 组 Eclipse 场景程序通过 OpenGL 参考编译链接。
- 这份包用于试读和隔离检查。真实存档、NVIDIA、Intel 与订阅冷启动是单独的验收范围。本机记录位于 `build/protected-r116-0.3.23/protection-report.json` 和 `validation.json`。
