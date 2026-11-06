#include <cmath>
#include "ozz/animation/runtime/ik_two_bone_job.h"
#include "ozz/base/maths/simd_quaternion.h"

// Thin C ABI over the unmodified MIT ozz IKTwoBoneJob. All matrices are
// column-major model-space matrices, quaternions are XYZW local corrections.
// Return: 1 reached, 0 valid but unreached, -1 invalid values, -2 invalid job.
extern "C" __declspec(dllexport) int aimoh_two_bone(
    const float* matrices, const float* target, const float* pole,
    const float* mid_axis, float weight, float soften, float twist,
    float* rotations) noexcept {
  if (!matrices || !target || !pole || !mid_axis || !rotations) return -1;
  for (int i = 0; i < 48; ++i) if (!std::isfinite(matrices[i])) return -1;
  for (int i = 0; i < 3; ++i)
    if (!std::isfinite(target[i]) || !std::isfinite(pole[i]) ||
        !std::isfinite(mid_axis[i])) return -1;
  if (!std::isfinite(weight) || !std::isfinite(soften) || !std::isfinite(twist))
    return -1;
  ozz::math::Float4x4 joints[3];
  for (int j = 0; j < 3; ++j)
    for (int c = 0; c < 4; ++c)
      joints[j].cols[c] = ozz::math::simd_float4::LoadPtrU(matrices + j * 16 + c * 4);
  ozz::animation::IKTwoBoneJob job;
  job.start_joint = &joints[0];
  job.mid_joint = &joints[1];
  job.end_joint = &joints[2];
  job.target = ozz::math::simd_float4::Load3PtrU(target);
  job.pole_vector = ozz::math::simd_float4::Load3PtrU(pole);
  job.mid_axis = ozz::math::simd_float4::Load3PtrU(mid_axis);
  job.weight = weight;
  job.soften = soften;
  job.twist_angle = twist;
  bool reached = false;
  job.reached = &reached;
  ozz::math::SimdQuaternion start, mid;
  job.start_joint_correction = &start;
  job.mid_joint_correction = &mid;
  if (!job.Run()) return -2;
  ozz::math::StorePtrU(start.xyzw, rotations);
  ozz::math::StorePtrU(mid.xyzw, rotations + 4);
  return reached ? 1 : 0;
}
