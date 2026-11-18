"""Build the model-side PMX-MAT1 files from the baked source geometry."""

import argparse
import hashlib
import json
import math
from pathlib import Path
import sys

from source_geometry import new_directory, payload, read
import numpy as np


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pmx", required=True, type=Path)
    parser.add_argument("--profile", required=True, type=Path)
    parser.add_argument("--baked", required=True, type=Path)
    parser.add_argument(
        "--face-slot",
        required=True,
        type=int,
        action="append",
        help="Confirmed PMX face surface slot; repeat for a split face",
    )
    parser.add_argument("--forward", nargs=3, type=float, required=True)
    parser.add_argument("--up", nargs=3, type=float, required=True)
    parser.add_argument("--paths", type=Path, default=Path(".local/paths.json"))
    parser.add_argument(
        "--output", type=Path, required=True, help="New directory for the two sidecar files"
    )
    args = parser.parse_args()
    sys.path.insert(0, str(Path(read(args.paths)["pmx_reader_scripts"]).resolve(strict=True)))
    import pmx_reader

    pmx = pmx_reader.load(str(args.pmx.resolve(strict=True)))
    profile = read(args.profile)
    model = read(args.baked / "model.json")
    sha = hashlib.sha256(args.pmx.read_bytes()).hexdigest()
    n = len(pmx.vertices)
    if not (
        sha == profile["pmx_sha256"] == model["pmx_sha256"]
        and n == profile["vertices"] == model["original_pmx_vertices"]
        and len(pmx.materials) == model["original_pmx_materials"]
    ):
        raise ValueError("Model identity or topology mismatch")
    (head,) = [row["target_index"] for row in profile["mapping"] if row["source"] == "Bip01_Head"]
    origin = np.asarray(pmx.bones[head].location)
    faces = np.asarray(pmx.faces).reshape(-1)
    ends = np.cumsum([0] + [m.vertex_count for m in pmx.materials])
    if ends[-1] != len(faces) or any(i < 0 or i >= len(pmx.materials) for i in args.face_slot):
        raise ValueError("Invalid source face slot or face index count")
    indices = np.concatenate([faces[ends[i] : ends[i + 1]] for i in args.face_slot])
    points = np.asarray([v.co for v in pmx.vertices])[indices]
    radius = np.max(np.abs(points - origin), axis=0)
    axes = np.asarray([args.forward, args.up])
    if (
        not np.isfinite(radius).all()
        or min(radius) <= 0
        or not np.allclose(axes @ axes.T, np.eye(2), atol=0.01)
    ):
        raise ValueError("Degenerate face bounds or invalid head basis")
    attr = payload(model["attributes"], [args.baked / "data"])
    rows = np.frombuffer(attr, dtype="<f4", count=model["attribute_records"] * 24).reshape(-1, 24)
    vertex, first = np.unique(rows[:, 3], return_index=True)
    if np.any((vertex < 1) | (vertex > n) | (vertex != np.floor(vertex))):
        raise ValueError("Invalid PMX vertex identity")
    uv = np.zeros((n, 2), dtype="<f4")
    uv[vertex.astype(int) - 1] = rows[first, 14:16]
    if not np.isfinite(uv).all() or np.any(abs(uv) >= 8):
        raise ValueError("Secondary UV outside PMX-MAT1 range")
    raw = uv.tobytes()
    filename = "uv1-danrp.f32"
    header = {
        "format": 1,
        "protocol": "PMX-MAT1",
        "pmx_sha256": sha,
        "vertices": n,
        "head_bone": head,
        "head_origin": origin.tolist(),
        "head_forward": args.forward,
        "head_up": args.up,
        "head_radius": radius.tolist(),
        "material_ids": list(range(len(pmx.materials))),
        "material_kinds": [0] * len(pmx.materials),
        "uv1": filename,
        "uv1_sha256": hashlib.sha256(raw).hexdigest(),
        "danrp_payload": 1,
        "shader_outline": False,
        "vertex_texture_width": 1024,
        "vertex_texture_height": math.ceil(n * 4 / 1024),
    }
    with new_directory(args.output) as out:
        (out / filename).write_bytes(raw)
        (out / "shading-danrp.json").write_text(json.dumps(header, indent=2), encoding="utf8")
    print(header["uv1_sha256"])


if __name__ == "__main__":
    main()
