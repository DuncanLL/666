"""Follow a packaged source part through its material, bindings and shader reflection."""

import argparse
import hashlib
import json
from pathlib import Path
import struct

from source_geometry import payload, read


def buffer_bytes(size, bindings):
    block = bytearray(size)
    for field in bindings:
        offset = field["byte_offset"]
        count = field["components"]
        if (
            offset < 0
            or offset % 4
            or count < 1
            or len(field["value"]) != count
            or offset + 4 * count > size
        ):
            raise ValueError(f"Invalid material field: {field['name']}")
        struct.pack_into("<" + "f" * count, block, offset, *field["value"])
    return bytes(block)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core-media", type=Path, required=True)
    parser.add_argument("--role-media", type=Path, required=True)
    parser.add_argument("--pack", required=True)
    parser.add_argument("--model-id", required=True)
    parser.add_argument(
        "--part", type=int, required=True, help="Index in the model descriptor's parts array"
    )
    parser.add_argument("--pass-name", required=True)
    parser.add_argument(
        "--write-buffer", type=Path, help="Write the verified, rebuilt parameter block to a new file"
    )
    args = parser.parse_args()
    core, role = args.core_media, args.role_media
    registry = read(core / "gfl2_pmx/registry.json")
    if args.model_id not in registry["packs"][args.pack]:
        raise ValueError("Model ID absent from the framework pack registry")
    role_registry = read(role / "gfl2_pmx/packs" / (args.pack + ".json"))
    (row,) = [r for r in role_registry["models"] if r["id"] == args.model_id]
    root = (role / "gfl2_pmx").resolve()

    def model_path(name):
        path = (root / row[name]).resolve(strict=True)
        path.relative_to(root)
        return path

    pmx = model_path("model")
    profile = read(model_path("profile"))
    sha = hashlib.sha256(pmx.read_bytes()).hexdigest()
    sidecar = read(pmx.parent / "shading-danrp.json")
    if sha != profile["pmx_sha256"] or sha != sidecar["pmx_sha256"]:
        raise ValueError("Profile or shading sidecar refers to another model")
    base = read(core / "gfl2_rendering/characters.json")["characters"]
    r = read(role / "gfl2_rendering/packs" / (args.pack + ".json"))["characters"]
    (model,) = [m for m in r["models"] if m["pmx_sha256"] == sha]
    if args.part < 0:
        raise ValueError("Expected nonnegative part index")
    part = model["parts"][args.part]
    material = r["materials"][part["source_material"]]
    if not part["source_identity_resolved"] or material["id"] != part["source_material"]:
        raise ValueError("Unresolved source material")
    roots = [role / "gfl2_rendering/data", core / "gfl2_rendering/data"]
    block = buffer_bytes(material["bytes"], material["bindings"])
    if block != payload(material["buffer"], roots):
        raise ValueError("Material buffer differs from its field layout and values")
    textures = {}
    for slot, binding in material["texture_bindings"].items():
        key = binding.get("resource")
        if not key:
            continue
        texture = (r["textures"] if key in r["textures"] else base["textures"])[key]
        payload(texture["payload"], roots)
        metadata = texture["metadata"]
        textures[slot] = {
            "resource": key,
            "sha256": texture["payload"]["sha256"],
            "format": metadata["m_TextureFormat"],
            "color_space": metadata["m_ColorSpace"],
            "mips": metadata["m_MipCount"],
            "scale": binding["scale"],
            "offset": binding["offset"],
        }
    stages = {}
    for choice in r["programs"]:
        if (choice["material"], choice["pass"]) != (material["name"], args.pass_name):
            continue
        rows = stages.setdefault(choice["stage"], [])
        for program in choice["programs"]:
            key = program["sha256"]
            glsl = (r["glsl"] if key in r["glsl"] else base["glsl"])[key]
            payload(glsl, roots)
            reflection = (r["consumed"] if key in r["consumed"] else base["consumed"])[key]
            rows.append(
                {
                    "program": key,
                    "glsl": glsl["sha256"],
                    "textures": program["textures"],
                    "samplers": program["samplers"],
                    "buffers": [b["name"] for b in reflection["buffers"]],
                }
            )
    if not {"progVertex", "progFragment"} <= stages.keys():
        raise ValueError("Could not trace both shader stages for the selected material and pass")
    if args.write_buffer:
        with args.write_buffer.open("xb") as file:
            file.write(block)
    print(
        json.dumps(
            {
                "pmx_sha256": sha,
                "vertices": profile["vertices"],
                "part": part["name"],
                "pmx_slot": part["pmx_material"],
                "local_source_material": material["id"],
                "material": material["name"],
                "shader": material["shader"],
                "pass": args.pass_name,
                "root": model["renderer_frames"]["materials"].get(str(material["id"])),
                "buffer": material["buffer"],
                "textures": textures,
                "stages": stages,
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
