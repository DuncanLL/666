# 人物光影跨显卡检查与发布标准

适用于公共人物着色器、接入层、材质输入、光影参数/菜单，以及新增角色与VP版本迁移。**作者AMD能跑只算一个设备的结果，不能据此宣布NVIDIA/Intel兼容通过。** 未测、阻断和错误不得记PASS。

## 每次必须核对

1. **身份与分发**：实际VP原JAR/签名、接入JAR、内嵌光影、人物核心、catalog/profile/PMX及资源SHA。安装与检查使用同一份产物；用户目录、空格/中文路径、工坊依赖、托管更新与自定义文件保留均要核。不能仅验证作者已有缓存。
2. **源码可移植性**：自有GLSL声明避开新旧保留名及驱动歧义名；不能因AMD/glslang接受就保留`packed`等已知问题。核显式版本、扩展/能力条件、阶段in/out类型与位置、UBO/SSBO布局/绑定、矩阵/数组、采样器类型、图片格式与读写权限、同步/屏障。compute有barrier时所有参与线程必须先到达，不能在边界线程中提前退出。
3. **最终源码编译/链接**：通过实际宿主完成继承、宏、include、原程序适配与阶段接口分配，再导出完整源码，用OpenGL GLSL参考编译/链接检查。涵盖所有当前源/早期变体及compute/geometry/反射服务，覆盖常量与菜单实时选项。禁止直接编原ZIP、使用Vulkan结果替代OpenGL、删除功能来讨好参考编译器。参考通过仍不等于任一厂商硬件通过。
4. **格式与预算**：核原纹理BC7/DXT/RGBA16F、色域、mip、alpha、ST、采样状态。检查实际GPU支持、纹理大小、UBO大小、各阶段采样器/纹理单位、image/SSBO、FBO完整性和几何着色器的总输出预算。总预算按输出顶点数乘每顶点输出分量核对。Eclipse的`gbuffers_terrain`须以官方VP展开的顶点、曲面细分控制、曲面细分求值和片元四阶段链接。VP提供PZ的地形与草资源，无需Minecraft草块扩展几何阶段。记录GPU型号、驱动和OpenGL版本。
5. **实际绘制与生命周期**：本次支持的VP版本均检查；包括正常启动、打开/关闭设置菜单、拖动滑块、选预设、切包/再进入、模型/纹理未就绪及恢复。主要画面用阿斯缇亚、夏安、科莱达；公共大改覆盖三者，新角色再核其实际身份、全部活动原程序、贴图与配件组合。夜间/室内/透明/发光眼等受影响路径必须有针对性验证，不能只看白天正面。只改无关UI文字不重跑大量画面。
6. **原环境与性能**：同一VP、预设、配置、相机、时间比较；先排除预热、诊断同步查询和争用。空场景检查不被人物链路改变。角色改动只作用人物；不以降模型/缩图/关效果冒充无损优化。静态映射、实际绘制、完整游戏FPS及用户观感分开。

## 三家显卡的结果怎样记录

- `AMD`、`NVIDIA`、`Intel`分别记录型号、驱动、宿主SHA、JAR/光影/人物资料SHA、具体测试与原日志。另一台不同厂商设备的结果不能由作者AMD结果推导。
- 分别保留语法/链接、格式/预算、组件绘制、真实游戏、多人、订阅冷启动的结果。没有做的层级标未测。诊断夹具通过不等于用户实际存档通过。
- 报错时先读最内层原因并对应最终源码行，区别词法、接口、资源缺失、显存/内存、磁盘和其它MOD问题。保留原失败记录；共用修复优先，不按人物名或显卡品牌写补光/换贴图补丁。
- 有已知未修编译/资源错误：`BLOCKED`。基础项通过但缺厂家实机：`LIMITED_HARDWARE_VALIDATION`，只能声明已测试设备、作为兼容候选交付。三家驱动证据齐全：`THREE_VENDOR_DRIVER_VALIDATED`，仍只覆盖记录的设备/驱动；不能宣传所有显卡或所有游戏流程零问题。
- 无法取得外部设备时，先完成所有不依赖外部设备的修复和检查，明确剩下的实机缺口；不无期限等待、不虚构PASS，也不妨碍用户选择上传范围明确的候选修复版。

## 工具入口

普通构建、组件启动和候选目录保持原入口。`check_render_support.py`只代表启动/资源发现，其报告明确标注不能据此认定跨显卡兼容。

Eclipse 启动检查还会用官方 Iris 配置器核对 PZ 材质映射、体积纹理和计算阶段。关闭 LPV 时，不分配体积光照纹理，不启动空的 LPV 计算程序。PZ 水面交互仍可使用自己的小型遮罩。开启 LPV 时，保留计算程序和 16 位体素遮罩。

```powershell
python tools/build_render_support.py
python tools/build_eclipse_support.py
python tools/build_combined_render_support.py
python tools/package_render_support.py --output <新工坊项目目录> --metadata-from <现有工坊项目目录>
python tools/check_render_support.py --project <新工坊项目目录>
python tools/check_render_release.py --evidence <evidence.json> --out <result.json>
python tools/check_render_release.py --evidence <evidence.json> --out <public-result.json> --public
python tools/package_render_support.py --output <新工坊项目目录> --metadata-from <现有工坊项目目录> --public --evidence <Native证据.json> --eclipse-evidence <Eclipse证据.json>
```

`--public`要求三家驱动证据齐全才通过。没有此标志的目录是候选，不能把候选目录生成成功当作跨显卡发布验收。上传仍由用户决定，工具不会自动上传。

证据 JSON 用 `format=1` 和 `backend=native` 或 `backend=eclipse`，缺省为 Native。包含当前 `jar_sha256`、`shader_sha256`、`character_jar_sha256`、`catalog_sha256`，以及 `startup_report`、`reference_reports[]`、`driver_reports[]`、`changed_models[]`、`integration_reports[]`。路径可相对于证据文件。检查器核对报告与当前源码、产物、角色核心、目录、宿主和编译器的指纹，并核对 GL 错误和新增人物契约。使用实际启动、编译和驱动输出，测试夹具的伪造反例仅供单元测试。

合包的两份证据都填 `distribution=combined`。它们共用合包 JAR 指纹和启动报告，分别填写各自的内嵌光影指纹、参考编译和驱动报告。

2026-11-08实例：NVIDIA4070 Laptop的旧`packed`词法问题已修；150项参考检查和AMD下RC2/RC3检查通过，NVIDIA/Intel修正版实机反馈缺失，因此0.2.9只能归为有限硬件验证，不能写“三家兼容完成”。
