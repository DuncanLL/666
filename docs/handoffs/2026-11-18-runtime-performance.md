# 运行时与光影性能复审 · 2026-11-18

基线为 `c515051`，PZ 42.22.2、官方 Viewpoint 0.1.7 和 RX 9070 XT。两份候选使用相同的角色、场景、相机、动画相位和画质设置。机器上启用了 25 个角色包。CPU 样本使用罗蕾莱原皮，98,078 顶点、497 根骨骼。[完整数值与指纹](../../provenance/performance/2026-11-18-runtime.json)

| 验收项 | 修改前实测 | 修改后实测 | 验收结果 |
| --- | --- | --- | --- |
| Native 完整一帧 GL 状态查询 | 3,668 次 | 713 次 | 减少 80.56%，状态断言通过 |
| DanRP 显示插值 | 0.4739 ms，895,236 次有限性检查 | 0.3192 ms，0 次数组有限性检查 | 保留原始捕获校验，GPU 打包分支直接取整帧保守包围盒 |
| 身体重定向，后续握持需要最终调色板 | 10 次完整求值，0.3375 ms，Java 堆分配 17,304 B/次 | 2 次完整求值，0.0754 ms，Java 堆分配 0 B/次 | 71 个登记模型、每个 3 个姿势，所有矩阵最大绝对误差 0 |
| 角色目录及材质冷初始化 | 1,350.1858 ms，1,204 个材质 UBO | 1,077.5897 ms，0 个材质 UBO | 全量触发后 1,204 个材质逐字节读回通过 |
| 整备室浏览预览冷开 / 热切 | 106.1285 / 38.8906 ms，Bullet / Chest 各 1 个世界 | 53.7535 / 17.9303 ms，Bullet / Chest 均为 0 | 待机手部矩阵最大绝对误差 0 |

### 实现边界

- 着色器使用的 126 号纹理在本版是 2D 属性图。VP 的 `ModelPass.end` 还会解除 64 至 79 号采样器。Native 阴影、早期深度、颜色、房间反射以及 Eclipse / 玻璃各自保存相应绑定。兼容配置使用 OpenGL 固定状态栈保留广播混合和颜色遮罩，核心配置使用显式快照。材质纹理按实际目标首次写入时记录。开启 `-Dgfl2.verifyGlScope=true` 会额外读取状态用于断言，这些查询另行计量。
- 房间反射的六面光照原先反复从场景光照程序读回整套 uniform。现在复用该程序，仅暂存探针覆盖的视角、矩阵与采样设置，结束时恢复 GPU 值，保留 VP 主视角的 CPU 缓存。固定时钟下，Native 和 Eclipse 的日景及有灯夜间室内画面 RMSE 均为 0。完整 Eclipse 日景查询从 1,932 降到 701。Native 日景样本内有两个实际提交的阴影视图、深度预通道和颜色通道。
- 身体重定向沿用原有局部四元数算法。中间层用 Rust 的原生 PMX 局部矩阵更新所需父链；仍有逐层 JNI 调用。需要真实 IK、复杂赋予链、姿势 Morph 或过渡结果时回退完整求值。测试覆盖参考姿势、源弯曲姿势和从 PZ 的 Bob_Idle 采样的姿势。
- 显示用的位置、法线与头部缓冲原先已经复用。本次去掉插值视图的重复扫描，浮点插值用双精度差值防止两个有限端点相减时溢出。Eclipse 上一帧顶点和旋转仍在原调色板位置。
- 每个角色分片通过只读冲突检查和局部重映射后才追加到共享索引。材质寄存器布局在构造时检查，首次使用某材质时再读文件、核 SHA、创建 UBO。浏览预览使用独立无物理加载入口，保留待机手势；握持校准仍走完整会话。换装查询读取内存中的选项与可见性，存档仍使用原 JSON 格式。

### 独立复跑

在性能分支工作树中配置 `.local/paths.json`，先运行 `py -3.12 tools/build_native.py` 和 `py -3.12 tools/build.py`。新版角色 JAR 需要本分支构建的引擎 DLL。随后依次运行 `build_render_support.py`、`build_eclipse_support.py`、`build_combined_render_support.py` 和既有保护构建流程。使用同一组角色输入对基线与候选各构建一次。

复审夹具含游戏待机矩阵、骨名、离屏场景与源码采集 Agent。下列 PowerShell 示例中的路径变量对应待测的角色 JAR、渲染支持 JAR、工坊作者目录、解包夹具目录与独立输出目录。场景脚本使用 JPype、NumPy、Pillow、ModernGL 和 glcontext。

```powershell
py -3.12 tools/check_runtime_perf.py --core $core --render $rawRender --workshop $workshop --model pmx-f2a800c48ca2b031 --idle-f32 "$fixture/idle.f32" --out "$out/cpu" --all-poses
py -3.12 tools/check_runtime_perf.py --core $core --render $rawRender --workshop $workshop --model pmx-f2a800c48ca2b031 --idle-f32 "$fixture/idle.f32" --out "$out/finite" --count-finite
py -3.12 tools/check_perf_regressions.py --core $core --render $rawRender --out "$out/regressions"
py -3.12 tools/check_perf_scene.py --core $core --render $sealedRender --workshop $workshop --fixture $fixture --out "$out/frames"
py -3.12 tools/check_perf_scene.py --core $core --render $sealedRender --workshop $workshop --fixture $fixture --out "$out/audit" --audit
py -3.12 tools/check_perf_scene.py --core $core --render $sealedRender --workshop $workshop --fixture $fixture --out "$out/night" --night --audit
py -3.12 tools/check_perf_scene.py --core $core --render $rawRender --workshop $workshop --fixture $fixture --out "$out/catalog" --catalog
```

CPU 计时取三个新 JVM 的中位数。冷开是进程内首次创建预览，热切是销毁后再次创建。插值使用真实 PMX 位置、法线、材质和 DanRP 历史。有限性次数在独立插桩运行中核对。GL 计数覆盖测量线程上 PZ、VP 与人物框架的 Java OpenGL 查询调用点。渲染 15 帧，取最后 6 帧中位数。日夜截图比较、GL 状态断言和查询次数分别记录。

`test_build.py` 只检验重复构建，另行运行了生命周期、GPU 流式上传、源姿态、握持、暂停、变形运动和源着色器链接检查。当前版本忽略存档中的旧胸部强度滑块，生命周期夹具检查这一实际契约，并通过无效武器标定及无效会话字段验证回滚。旧单目录夹具使用当前角色分包的模型路径生成临时登记表。

Native、Somber、Eclipse 三个构建分别通过 69、69、109 组宿主展开后的 OpenGL 参考链接。每个后端的全目录检查涵盖 72 个渲染资料、63 种实际链接程序组合与全部材质读回。密封包通过正常启动、重新发现和内嵌文件指纹检查。三份发布检查均为 `LIMITED_HARDWARE_VALIDATION`，尚缺 NVIDIA、Intel 实机与实际存档游玩验收。
