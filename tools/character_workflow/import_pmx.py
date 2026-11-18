"""Generate and read back a model directory for the shared PMX runtime (Windows x64)."""

import argparse
from multiprocessing import get_context
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pmx", type=Path, required=True)
    parser.add_argument(
        "--source-poses",
        type=Path,
        required=True,
        help="B42 source_bone_names, source_reference and source_bend from a verified profile",
    )
    parser.add_argument(
        "--output", type=Path, required=True, help="New model directory, e.g. models/pmx-..."
    )
    parser.add_argument("--paths", type=Path, default=Path(".local/paths.json"))
    parser.add_argument("--runtime-jar", type=Path, help="Character JAR with bundled native libraries")
    args = parser.parse_args()
    cfg = json.loads(args.paths.read_text("utf-8-sig"))
    model = args.pmx.resolve(strict=True)
    poses = json.loads(args.source_poses.read_bytes())
    output = args.output.resolve()
    if output.exists() or output.is_relative_to(model.parent):
        parser.error("Use a new output directory outside the source model directory")
    game = Path(cfg["game_root"]).resolve(strict=True)
    jar = Path(args.runtime_jar or cfg["reference_jar"]).resolve(strict=True)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as temporary:
        sandbox = Path(temporary)
        stage = sandbox / "model"
        stage.mkdir()
        # Windows holds the mapped native DLLs until their process exits.
        worker = get_context("spawn").Process(
            target=build_model, args=(cfg, model, poses, game, jar, sandbox, stage)
        )
        worker.start()
        worker.join()
        if worker.exitcode != 0:
            raise SystemExit(worker.exitcode)
        stage.rename(output)
    profile = json.loads((output / "profile.json").read_bytes())
    print(
        json.dumps(
            {
                "model": output.name,
                "pmx_sha256": profile["pmx_sha256"],
                "bones": profile["bones"],
                "vertices": profile["vertices"],
                "palm_ready": profile["palm_ready"],
                "physics_enabled": profile["physics_enabled"],
                "scope": "PMX reference and bend pose readback",
            },
            ensure_ascii=False,
        )
    )


def build_model(cfg, model, poses, game, jar, sandbox, stage):
    if cfg.get("python_dependencies"):
        sys.path.insert(0, str(Path(cfg["python_dependencies"]).resolve(strict=True)))
    sys.path.insert(0, str(Path(cfg["pmx_reader_scripts"]).resolve(strict=True)))
    import jpype
    import numpy as np
    import pmx_reader
    from calibration import calibrate

    names = poses["source_bone_names"]
    if len(set(names)) != len(names):
        raise ValueError("Duplicate PZ source bone names")

    def pose(key):
        matrices = np.asarray(poses[key], dtype="f4").reshape(len(names), 4, 4)
        if not np.isfinite(matrices).all():
            raise ValueError("Non-finite " + key)
        return matrices.transpose(0, 2, 1)

    reference, bend = pose("source_reference"), pose("source_bend")
    pmx = stage / "model.pmx"
    shutil.copy2(model, pmx)
    original = pmx_reader.load(str(model))
    required = {m.texture for m in original.materials if m.texture >= 0}

    def copy_texture(source):
        source = source.resolve(strict=True)
        relative = source.relative_to(model.parent)
        destination = stage / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        return relative.as_posix()

    for i, texture in enumerate(original.textures):
        path = Path(texture.path)
        if i in required or path.is_file():
            copy_texture(path)
    controls = model.with_suffix(".controls.json")
    if controls.is_file():
        shutil.copy2(controls, pmx.with_suffix(".controls.json"))
        appearance = json.loads(controls.read_bytes()).get("appearance") or {}
        for texture in appearance.get("texture_overrides", []):
            if texture:
                if Path(texture).is_absolute():
                    raise ValueError("Use a model-relative texture override")
                copy_texture(model.parent / texture)
    classpath = [jar, cfg["viewpoint_jar"], cfg["zombiebuddy_jar"], *game.glob("*.jar")]
    os.chdir(game)
    jpype.startJVM(
        str(game / "jre64/bin/server/jvm.dll"),
        "--enable-native-access=ALL-UNNAMED",
        "-Duser.home=" + str(sandbox),
        "-Djava.io.tmpdir=" + str(sandbox),
        classpath=list(map(str, classpath)),
        convertStrings=True,
    )
    try:
        J = jpype.JClass
        jpath = J("java.nio.file.Path").of
        fs = J("zombie.ZomboidFileSystem").instance
        fs.setCacheDir(str(sandbox))
        fs.init()
        main_class = J("gfl2.runtime.Main").class_
        directory = main_class.getDeclaredField("directory")
        directory.setAccessible(True)
        directory.set(None, jpath(str(sandbox)))
        initialize = main_class.getDeclaredMethod("nativeRuntime")
        initialize.setAccessible(True)
        initialize.invoke(None)
        field = main_class.getDeclaredField("api")
        field.setAccessible(True)
        api = field.get(None)
        profile = calibrate(J, jpype, api, pmx, names, reference, bend)
        profile.update(source_pmx="model.pmx", hand_relax_strength=0.7)
        if not controls.is_file():
            profile["label"] = model.stem
        for material in profile["appearance_catalog"]["materials"]:
            if material["texture"]:
                texture = Path(material["texture"]).resolve(strict=True)
                material["texture"] = texture.relative_to(stage).as_posix()
        descriptor = stage / "profile.json"
        descriptor.write_text(
            json.dumps(profile, ensure_ascii=False, allow_nan=False, indent=2) + "\n", "utf8"
        )
        loaded = J("gfl2.motion.PmxModel").load(api, jpath(str(pmx)), jpath(str(descriptor)))
        try:
            for key in ("source_reference", "source_bend"):
                loaded.apply(jpype.JArray(jpype.JFloat)(profile[key]), None, jpype.JFloat(1 / 60))
                positions = J("java.nio.ByteBuffer").allocateDirect(profile["vertices"] * 12)
                api.CopyDataToByteBuffer(positions, api.GetPoss(loaded.handle()), positions.capacity())
                if not np.isfinite(np.frombuffer(memoryview(positions), dtype="<f4")).all():
                    raise ValueError("Non-finite posed vertices: " + key)
        finally:
            loaded.close()
    finally:
        jpype.shutdownJVM()


if __name__ == "__main__":
    main()
