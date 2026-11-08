# 人物光影跨显卡发布标准落地

用户明确要求以后检查NVIDIA/Intel，不再把作者AMD能跑等同发布通过。

完成：

- `docs/render-release-standard.md`覆盖输入身份与分发、GLSL新旧标识符/版本/接口、最终宿主展开后参考编译链接、格式与GPU预算、菜单/加载/切包生命周期、三基准与新人物、环境隔离及性能证据边界。
- AGENTS与统一技能的两种模式均引用该标准；技能新增`references/rendering/cross-gpu-release.md`，避免只在本对话记住。
- `tools/check_render_release.py`核当前JAR/内嵌光影/源码、人物核心与catalog、启动报告、参考源码与编译器指纹、实际驱动GL错误、声明VP版本覆盖、当前人物程序/材质验证和新增人物契约。报告分BLOCKED／LIMITED_HARDWARE_VALIDATION／THREE_VENDOR_DRIVER_VALIDATED。
- `--public`缺任何一家厂商实际驱动结果即非零退出。工坊打包入口同样增加`--public --evidence`检查；普通输出明确记录为候选，不自动上传。
- 原组件检查报告增加`STARTUP_AND_RESOURCE_DISCOVERY_ONLY`与跨显卡未建立的范围字段。
- 8项隔离假证据单元测试通过；假数据仅存在临时目录，不能作为发布证据。测试涵盖AMD单家必须limited，以及过期JAR、展开源码变化、驱动错误、新人物遗漏、参考失败、宿主遗漏、全catalog覆盖缺失必须阻断。

实际0.2.9证据使用本机保存的150项参考检查、RC2/RC3 AMD驱动报告、当前0.3.9人物JAR和13模型catalog及芙洛伦契约。检查结果：errors为空、AMD已有、NVIDIA/Intel缺测，状态LIMITED_HARDWARE_VALIDATION；`--public`正确拒绝。没有把对方旧版NVIDIA失败日志当修正版通过，也没有编造Intel结果。

本轮不修改运行时JAR、玩家设置或现装游戏，不重跑大量GPU画面，不上传工坊。源码与技能约束用于后续变更；现有0.2.9的真实兼容范围也按此标准记录。
