# 局部体积软体预览的来源与修改

最初实验入口为 `experiments/softbody`。用户认可 V3 视觉后，公共实现移入 `native/chest-softbody/softbody.cpp` 并接入 0.3.0 游戏试装；构建不自动安装或发布。

- Bullet 上游固定版本：`63c4d67e337017f9d8b298c900e9aabdb69296e7`。
- 体积弹性：上游 `btDeformableNeoHookeanForce`，其公式明确引用 Pixar *Stable Neo-Hookean Flesh Simulation*。
- 世界、隐式时间积分、共轭梯度、弹性及阻尼均复用 Bullet；不自行实现 PMX 解析器或 FEM 求解器。
- 低分辨率局部体积通过上游重心插值传递给原始网格。区域取自原 PMX 的有效蒙皮权重及骨骼资料，不按人物名、骨号或动作名分支。
- 蒙皮位移范围约束参考 Jolt `SoftBodySharedSettings::Skinned::mMaxDistance` 的公开设计；独立实现少量位置/速度投影，没有引入 Jolt 运行库或复制其代码。

## 对上游代码的两处修正

文件：`native/mmd-engine/deps/bullet3/src/BulletSoftBody/btDeformableBackwardEulerObjective.cpp`，修改处用 `R116` 注释标明。

1. 隐式 `applyForce` 的全局力数组索引必须对固定节点也递增。上游只在非零逆质量分支中递增，与 `updateId()` 的全节点索引不一致，会影响含固定点的模型和多个软体。
2. 可选重力向量初始化为零，避免没有注册重力力时读取未初始化值。

`sb_check_fixed_force_index` 使用两个包含交错固定点的最小软体直接验证第一项；旧实现会把固定点的力错配到后续自由节点。原有许可继续保留，不把修改版描述为未经修改的上游。

## 独立接入

我们实现公共 C ABI、原 PMX 权重区域识别、姿态/单位转换、固定点运动、速度缓存同步、蒙皮范围约束和原网格变形输出。外部速度更新同时维护 Bullet 隐式积分的 `m_v`、`m_vn`，避免按压或阻尼被其历史缓存覆盖。游戏 FFM、动画所有权 API、快照接线和参数菜单均为本项目独立实现。

本次没有使用 PZMMD 作者分支、二进制或其僵毁接入代码。工作台适配器和视觉素材只在本地，不提交此仓库。

## 边界

这是以作者静态形状为平衡参考的惯性/体积弹性系统；未实现身体、手、衣料接触或完整皮下组织模型。蒙皮范围约束会限制过大的变形；体积误差、实际可见形变和计算开销须一并报告。数值检查不替代视觉认可，0.3.0 游戏试装的实际游玩与性能验收仍待用户确认。
