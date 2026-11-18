"""Common PMX ingestion: retain source skin; generate model data for one action core."""

from pathlib import Path
import hashlib
import json
import re
import numpy as np
import pmx_reader


def catalog(model):
    def material_only(index, seen):
        if index in seen:
            return False
        morph = model.morphs[index]
        if type(morph).__name__ == "MaterialMorph":
            return bool(morph.offsets)
        if type(morph).__name__ != "GroupMorph":
            return False
        return bool(morph.offsets) and all(
            0 <= x.morph < len(model.morphs) and material_only(x.morph, seen | {index})
            for x in morph.offsets
        )

    return {
        "materials": [
            {
                "index": i,
                "name": m.name,
                "name_e": m.name_e,
                "base_alpha": float(m.diffuse[3]),
                "base_colour": list(m.diffuse[:3]),
                "base_double_sided": bool(m.is_double_sided),
                "texture": None if m.texture < 0 else model.textures[m.texture].path,
            }
            for i, m in enumerate(model.materials)
        ],
        "morphs": [
            {"index": i, "name": m.name, "kind": type(m).__name__}
            for i, m in enumerate(model.morphs)
            if m.category == 4 and material_only(i, set())
        ],
    }


def _key(name):
    return re.sub(r"[\s_.-]+", "", name).casefold()


def calibrate(J, jp, api, path, names, source_reference, source_bend):
    """No character names, per-animation poses or exported/rebound mesh are inputs."""
    path = Path(path).resolve(strict=True)
    source = pmx_reader.load(str(path))
    if not source.vertices or not source.materials:
        raise ValueError("Expected PMX vertices and materials")
    handle = int(api.LoadModelPMX(str(path), str(path.parent), 3))
    if not handle:
        raise RuntimeError("Native engine could not load " + str(path))
    source_index = {name: index for index, name in enumerate(names)}
    floats = lambda a: jp.JArray(jp.JFloat)(np.asarray(a, dtype="f4").ravel())
    ints = lambda a: jp.JArray(jp.JInt)(a)
    world = lambda a: floats(a.transpose(0, 2, 1))
    try:
        if source.rigids and not api.HasPhysics(handle):
            api.InitPhysics(handle)
        api.SetAutoBlinkEnabled(handle, False)
        api.SetPhysicsEnabled(handle, False)
        api.SetPZLegIKSuppressed(handle, True)
        api.InitGpuSkinningData(handle)
        metadata = json.loads(str(api.GetBoneMetadata(handle)))
        if (
            len(metadata) != len(source.bones)
            or len(source.vertices) != api.GetVertexCount(handle)
            or len(source.materials) != api.GetMaterialCount(handle)
        ):
            raise ValueError("PMX parser and native model topology differ")
        lookup = {}
        for index, bone in enumerate(source.bones):
            if bone.name != metadata[index]["name"]:
                raise ValueError("PMX/native bone order mismatch")
            for alias in [bone.name, bone.name_e]:
                if alias:
                    lookup.setdefault(_key(alias), index)
        roles = [
            ("Bip01", "全ての親", ["motherbone", "root"]),
            ("Bip01_Pelvis", "センター", ["center", "hips", "pelvis"]),
            ("Bip01_Spine", "上半身", ["upperbody", "spine"]),
            ("Bip01_Spine1", "上半身2", ["upperbody2", "chest"]),
            ("Bip01_Neck", "首", ["neck"]),
            ("Bip01_Head", "頭", ["head"]),
        ]
        for side, jp_side, en in [("L", "左", "left"), ("R", "右", "right")]:
            for suffix, name, alias in [
                ("Clavicle", "肩", "shoulder"),
                ("UpperArm", "腕", "arm"),
                ("Forearm", "ひじ", "elbow"),
                ("Hand", "手首", "wrist"),
                ("Thigh", "足", "leg"),
                ("Calf", "ひざ", "knee"),
                ("Foot", "足首", "ankle"),
            ]:
                roles.append(
                    ("Bip01_" + side + "_" + suffix, jp_side + name, [en + alias, alias + "_" + side])
                )
        mapped = []
        missing = []
        for native, name, aliases in roles:
            target = next((lookup[_key(n)] for n in [name, *aliases] if _key(n) in lookup), None)
            if target is None:
                missing.append(name)
            else:
                mapped.append((source_index[native], target, native, name))
        if missing:
            raise ValueError("Missing standard MMD body roles: " + ", ".join(missing))
        optional = []
        for side, jp_side in [("L", "左"), ("R", "右")]:
            for source_suffix, target in [
                ("Finger0", jp_side + "親指０"),
                *[("Finger1", jp_side + f + "１") for f in ["人指", "中指", "薬指", "小指"]],
            ]:
                if _key(target) in lookup:
                    optional.append(
                        (
                            source_index["Bip01_" + side + "_" + source_suffix],
                            lookup[_key(target)],
                            "Bip01_" + side + "_" + source_suffix,
                            target,
                        )
                    )
        mapped += optional
        target_ids = [x[1] for x in mapped]
        source_ids = [x[0] for x in mapped]
        if len(set(target_ids)) != len(target_ids):
            raise ValueError("Ambiguous body role assignment")
        parents = np.asarray([x["parent"] for x in metadata], dtype="i4")
        positions = np.asarray([x["initial_position"] for x in metadata], dtype="f4")
        qps = np.tile(np.array([0, 0, 0, 0, 0, 0, 1, 1, 1, 1], dtype="f4"), (len(mapped), 1))
        tpose = next(
            (m for m in source.morphs if _key(m.name) == "tpose" and type(m).__name__ == "BoneMorph"),
            None,
        )
        if tpose is not None:
            offsets = {x.index: x for x in tpose.offsets}
            for i, bone in enumerate(target_ids):
                if bone in offsets:
                    x = offsets[bone]
                    qps[i, :3] = x.location_offset
                    qps[i, 3:7] = x.rotation_offset
            reference_kind = "source-authored body reference, automatically discovered"
        else:
            reference_kind = "automatic mapped arm directions, no authored T-Pose required"
        # A pose named T-Pose does not certify matching segment directions: Bob_TPose bends its elbows.
        # Normalize every reference with the same RestFixer direction/parent-space method.
        palette = J("java.nio.ByteBuffer").allocateDirect(len(metadata) * 64)
        palette.order(J("java.nio.ByteOrder").nativeOrder())

        def commit():
            api.SetBoneAdditiveTransformBatch(handle, ints(target_ids), floats(qps))
            api.UpdateAnimationOnly(handle, 0)

        def joint(index):
            palette.clear()
            api.CopySkinningMatricesToBuffer(handle, palette)
            a = [float(palette.getFloat(index * 64 + k * 4)) for k in range(16)]
            return J("org.joml.Matrix4f")().set(floats(a)).translate(*map(float, positions[index]))

        def store_rotation(bone, rotation):
            parent = (
                J("org.joml.Quaternionf")()
                if parents[bone] < 0
                else joint(int(parents[bone]))
                .getUnnormalizedRotation(J("org.joml.Quaternionf")())
                .normalize()
            )
            local = parent.invert().mul(rotation).normalize()
            qps[target_ids.index(bone), 3:7] = [local.x(), local.y(), -local.z(), -local.w()]

        def align_segments(segments):
            for side in ["L", "R"]:
                for start, end in segments:
                    src = source_index["Bip01_" + side + "_" + start]
                    tail = source_index["Bip01_" + side + "_" + end]
                    bone = next(x[1] for x in mapped if x[0] == src)
                    target_tail = next(x[1] for x in mapped if x[0] == tail)
                    commit()
                    head = joint(bone)
                    tail_matrix = joint(target_tail)
                    a = head.getTranslation(J("org.joml.Vector3f")())
                    b = tail_matrix.getTranslation(J("org.joml.Vector3f")()).sub(a)
                    wanted = source_reference[tail, :3, 3] - source_reference[src, :3, 3]
                    wanted[2] *= -1
                    rotation = J("org.joml.Quaternionf")().rotationTo(
                        b, J("org.joml.Vector3f")(*map(float, wanted))
                    )
                    desired = rotation.mul(
                        head.getUnnormalizedRotation(J("org.joml.Quaternionf")()).normalize()
                    )
                    store_rotation(bone, desired)
            commit()

        align_segments([("Clavicle", "UpperArm"), ("UpperArm", "Forearm"), ("Forearm", "Hand")])
        commit()
        feet = [next(x[1] for x in mapped if x[2] == "Bip01_" + side + "_Foot") for side in ["L", "R"]]
        foot_rotations = [
            joint(bone).getUnnormalizedRotation(J("org.joml.Quaternionf")()).normalize() for bone in feet
        ]
        align_segments([("Thigh", "Calf"), ("Calf", "Foot")])
        # Preserve the source model's foot orientation as with the existing wrist orientation restoration.
        for bone, rotation in zip(feet, foot_rotations):
            store_rotation(bone, rotation)
        commit()
        by_source = {x[2]: x[1] for x in mapped}
        neck = by_source["Bip01_Neck"]
        root = by_source["Bip01"]
        # Same established Y-up, ground-origin scale as the existing common core.
        # Source Bip01 is a hip-level root; subtracting it would compare unlike anchors.
        scale = float(positions[neck, 1] / source_reference[source_index["Bip01_Neck"], 1, 3])
        if not np.isfinite(scale) or scale <= 0:
            raise ValueError("Invalid humanoid scale")
        core = J("gfl2.motion.WorldPoseRetargeter")(
            api,
            handle,
            ints(source_ids),
            ints(target_ids),
            ints(parents),
            floats(positions),
            world(source_reference),
            floats(qps),
            scale,
            source_index["Bip01"],
            root,
            source_index["Bip01_Pelvis"],
        )
        source_arms = ints(
            [
                source_index["Bip01_" + s + "_" + p]
                for s in ["L", "R"]
                for p in ["UpperArm", "Forearm", "Hand"]
            ]
        )
        target_arms = ints(
            [
                by_source["Bip01_" + s + "_" + p]
                for s in ["L", "R"]
                for p in ["UpperArm", "Forearm", "Hand"]
            ]
        )
        palm_names = [
            side + finger + "１" for side in ["左", "右"] for finger in ["人指", "中指", "薬指", "小指"]
        ]
        palm_ready = all(_key(n) in lookup for n in palm_names)
        palm = [
            [source_index["Bip01_" + s + "_Hand"] for s in ["L", "R"]],
            [source_index["Bip01_" + s + "_Finger1"] for s in ["L", "R"]],
            [by_source["Bip01_" + s + "_Hand"] for s in ["L", "R"]],
            [lookup[_key(n)] for n in palm_names] if palm_ready else [],
        ]
        if palm_ready:
            core.calibratePalms(*(ints(a) for a in palm))
        core.apply(world(source_bend))
        axes = [float(x) for x in core.calibrateGripAxes(target_arms)]
        dynamic = [b for b in source.rigids if b.mode != 0 and b.bone is not None]
        protected = set(target_ids)
        for bone in target_ids:
            parent = int(parents[bone])
            chain = {bone}
            while parent >= 0:
                if parent >= len(parents) or parent in chain:
                    raise ValueError(f"Invalid parent chain for mapped bone {bone}")
                chain.add(parent)
                protected.add(parent)
                parent = int(parents[parent])
        conflicts = sorted({source.bones[b.bone].name for b in dynamic if b.bone in protected})
        available = bool(api.HasPhysics(handle)) and bool(dynamic)
        physics = {
            "mode": "authored",
            "available": available,
            "enabled": available and not conflicts,
            "rigid_bodies": len(source.rigids),
            "dynamic_bodies": len(dynamic),
            "joints": len(source.joints),
            "primary_bone_conflicts": conflicts,
            "hair_bodies": sum(
                any(k in (b.name + " " + source.bones[b.bone].name).casefold() for k in ["hair", "髪"])
                for b in dynamic
            ),
            "garment_bodies": sum(
                any(
                    k in (b.name + " " + source.bones[b.bone].name).casefold()
                    for k in ["skirt", "cloth", "dress", "スカート", "裙"]
                )
                for b in dynamic
            ),
        }
        core.setPhysicsEnabled(physics["enabled"])
        profile = {
            "format": 1,
            "source_pmx": str(path),
            "label": path.stem,
            "pmx_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "calibration_revision": 3,
            "leg_reference_aligned": True,
            "arm_reference_aligned": True,
            "physics": physics,
            "bones": len(metadata),
            "vertices": int(api.GetVertexCount(handle)),
            "reference": reference_kind,
            "mapping": [
                {"source": x[2], "target": metadata[x[1]]["name"], "target_index": x[1]} for x in mapped
            ],
            "source_ids": source_ids,
            "target_ids": target_ids,
            "parents": parents.tolist(),
            "source_bone_names": list(names),
            "initial_positions": positions.ravel().tolist(),
            "neutral_qps": qps.ravel().tolist(),
            "source_reference": source_reference.transpose(0, 2, 1).ravel().tolist(),
            "source_bend": source_bend.transpose(0, 2, 1).ravel().tolist(),
            "source_arms": list(map(int, source_arms)),
            "target_arms": list(map(int, target_arms)),
            "palm_source_hands": palm[0],
            "palm_source_knuckles": palm[1],
            "palm_target_hands": palm[2],
            "palm_target_knuckles": palm[3],
            "source_root": source_index["Bip01"],
            "target_root": root,
            "unit_scale": scale,
            "bend_axes": axes,
            "palm_ready": palm_ready,
            "mesh_rebound": False,
            "per_action_adjustments": 0,
            "facial_features_added": False,
            "physics_enabled": physics["enabled"],
        }
        appearance = J("gfl2.motion.ModelAppearance")(api, handle)
        profile["appearance"] = dict(json.loads(str(appearance.stateJson())), groups=[])
        profile["appearance_catalog"] = catalog(source)
        profile["appearance_controls"] = {"format": 1, "groups": [], "rules": []}
        sidecar = path.with_suffix(".controls.json")
        if sidecar.is_file():
            controls = json.loads(sidecar.read_text("utf-8"))
            if controls.get("pmx_sha256") != profile["pmx_sha256"]:
                raise ValueError("换装定义与 PMX 指纹不符")
            profile["appearance_controls"] = controls["controls"]
            profile["label"] = controls.get("label", profile["label"])
            profile["material_presets"] = controls.get("material_presets", [])
            if controls.get("appearance") is not None:
                profile["appearance"] = controls["appearance"]
        appearance.configure(
            json.dumps(profile["appearance"]), json.dumps(profile["appearance_controls"])
        )
        profile["appearance"] = json.loads(str(appearance.stateJson()))
        return profile
    finally:
        api.DeleteModel(handle)
