"""Bake one reflected float UnityPerMaterial buffer from the serialized source material."""

import argparse
import json
from pathlib import Path

from source_geometry import blob, new_directory, read
from trace_material import buffer_bytes


def material_bindings(tree, properties, layout):
    saved = tree["m_SavedProperties"]
    floats = dict(saved["m_Floats"])
    vectors = dict(saved["m_Colors"])
    textures = dict(saved["m_TexEnvs"])
    fields = []
    for field in layout:
        name = field["name"]
        prop_name = name.split("@", 1)[0]
        definition = properties.get(prop_name)
        if field["type"] != 0 or field["array_size"] != 0:
            raise ValueError(f"{name}: expected a reflected scalar or vector of floats")
        if prop_name in floats:
            values = [floats[prop_name]]
            origin = "serialized float"
        elif prop_name in vectors:
            values = [vectors[prop_name][key] for key in "rgba"]
            origin = "serialized color/vector"
        elif name.endswith("_ST") and name[:-3] in textures:
            binding = textures[name[:-3]]
            values = [binding[key][axis] for key in ("m_Scale", "m_Offset") for axis in "xy"]
            origin = "texture scale/offset"
        elif definition is not None:
            values = list(definition["value"])
            origin = "shader default"
        else:
            raise ValueError(f"{name}: no serialized value, texture ST or shader default")
        if definition is not None and definition["type"] == 0:
            values[:3] = [
                v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in values[:3]
            ]
            origin += " (linear RGB)"
        fields.append(
            {
                "name": name,
                "byte_offset": field["byte_offset"],
                "components": field["components"],
                "value": values[: field["components"]],
                "origin": origin,
            }
        )
    return fields


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--input", type=Path, required=True, help="JSON with material, properties, layout and bytes"
    )
    parser.add_argument(
        "--output", type=Path, required=True, help="A new directory for bindings.json and data/"
    )
    args = parser.parse_args()
    source = read(args.input)
    bindings = material_bindings(source["material"], source["properties"], source["layout"])
    data = buffer_bytes(source["bytes"], bindings)
    with new_directory(args.output) as out:
        (out / "data").mkdir()
        row = {
            "buffer": blob(out, data, ".f32"),
            "bindings": bindings,
            "binding_count": len(bindings),
            "bytes": len(data),
        }
        (out / "bindings.json").write_text(
            json.dumps(row, ensure_ascii=False, indent=2), encoding="utf8"
        )
    print(row["buffer"]["sha256"])


if __name__ == "__main__":
    main()
