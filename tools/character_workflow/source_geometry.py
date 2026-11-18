"""Read back or bake resolved PMX corner attributes using the runtime's format-2 layout."""

import argparse
from contextlib import contextmanager
import hashlib
import json
from pathlib import Path
import tempfile

import numpy as np


def read(path):
    return json.loads(path.read_bytes())


def digest(data):
    return hashlib.sha256(data).hexdigest()


@contextmanager
def new_directory(output):
    output = output.resolve()
    if output.exists():
        raise FileExistsError(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as scratch:
        directory = Path(scratch) / "result"
        directory.mkdir()
        yield directory
        directory.rename(output)


def payload(row, data_roots):
    path = Path(row["path"])
    if (
        row.get("storage") != "mod"
        or path.as_posix() != "gf2/data/" + path.name
        or path.stem != row["sha256"]
    ):
        raise ValueError(f"Invalid external resource descriptor: {path}")
    for root in data_roots:
        file = root / path.name
        if file.is_file():
            data = file.read_bytes()
            if len(data) != row["bytes"] or digest(data) != row["sha256"]:
                raise ValueError(f"Resource fingerprint mismatch: {file}")
            return data
    raise FileNotFoundError(path.name)


def blob(directory, data, suffix):
    checksum = digest(data)
    name = checksum + suffix
    (directory / "data" / name).write_bytes(data)
    return {"path": "gf2/data/" + name, "bytes": len(data), "sha256": checksum, "storage": "mod"}


def unpack(args, out):
    models = read(args.fragment)["characters"]["models"]
    (model,) = [m for m in models if m["pmx_sha256"] == args.sha]
    attrs = payload(model["attributes"], args.data)
    geom = payload(model["geometry"], args.data)
    size = model["texture_width"] * model["texture_height"] * 16
    if len(attrs) != size or model["format"] != 2:
        raise ValueError("Unexpected attribute size or model format")
    records = np.frombuffer(attrs, dtype="<f4", count=model["attribute_records"] * 24).reshape(-1, 24)
    meta = {
        key: model[key]
        for key in (
            "pmx_sha256",
            "original_pmx_vertices",
            "original_pmx_materials",
            "renderer_frames",
            "source_affine",
            "texture_width",
        )
    }
    meta["parts"] = []
    for i, part in enumerate(model["parts"]):
        if not part["source_identity_resolved"]:
            raise ValueError(f"Unresolved material for part {i}")
        pairs = np.frombuffer(
            geom, dtype="<u4", offset=part["vertex_offset"], count=part["vertices"] * 2
        ).reshape(-1, 2)
        indices = np.frombuffer(geom, dtype="<u4", offset=part["index_offset"], count=part["indices"])
        corners = pairs[indices]
        values = records[corners[:, 1]]
        if not np.array_equal(corners[:, 0] + 1, values[:, 3]):
            raise ValueError(f"Unresolved PMX vertex in part {i}")
        np.save(out / f"part-{i}.npy", values.reshape(-1, 3, 24))
        meta["parts"].append(
            {k: part[k] for k in ("name", "pmx_material", "source_material", "source_identity_resolved")}
        )
    (out / "draws.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf8")


def bake(args, out):
    meta = read(args.matched / "draws.json")
    profile = read(args.profile)
    sha = digest(args.pmx.read_bytes())
    if not (sha == meta["pmx_sha256"] == profile["pmx_sha256"] == meta["renderer_frames"]["pmx_sha256"]):
        raise ValueError(
            "Model, matched geometry, renderer roots and profile must have the same PMX digest"
        )
    vertices = profile["vertices"]
    materials = len(profile["appearance_catalog"]["materials"])
    if (vertices, materials) != (meta["original_pmx_vertices"], meta["original_pmx_materials"]):
        raise ValueError("PMX topology mismatch")
    bank = {}
    geometry = bytearray()
    parts = []
    for i, part in enumerate(meta["parts"]):
        if part.get("source_identity_resolved") is not True:
            raise ValueError(f"Missing source material confirmation for part {i}")
        if not 0 <= part["pmx_material"] < materials or part["source_material"] < 0:
            raise ValueError(f"Invalid material index in part {i}")
        triangles = np.load(args.matched / f"part-{i}.npy", allow_pickle=False)
        if triangles.ndim != 3 or triangles.shape[1:] != (3, 24) or not len(triangles):
            raise ValueError(f"Expected triangles of 3 x 24 floats in part {i}")
        rows = np.ascontiguousarray(triangles, dtype="<f4").reshape(-1, 24)
        ids = rows[:, 3]
        bone_ids, weights = rows[:, 16:20], rows[:, 20:24]
        used = weights != 0
        if (
            not np.isfinite(rows).all()
            or np.any((ids < 1) | (ids > vertices) | (ids != np.floor(ids)))
            or np.any(np.abs(rows[:, 7]) != 1)
            or np.any(weights < 0)
            or not np.allclose(weights.sum(axis=1), 1, atol=0.0001)
            or np.any(
                used
                & ((bone_ids < 0) | (bone_ids >= profile["bones"]) | (bone_ids != np.floor(bone_ids)))
            )
        ):
            raise ValueError(f"Invalid source vertex, tangent sign or skinning weights in part {i}")
        pairs = {}
        indices = []
        for row in rows:
            record = bank.setdefault(row.tobytes(), len(bank))
            pair = (int(row[3]) - 1, record)
            indices.append(pairs.setdefault(pair, len(pairs)))
        vertex_offset = len(geometry)
        geometry.extend(np.asarray(list(pairs), dtype="<u4").tobytes())
        index_offset = len(geometry)
        geometry.extend(np.asarray(indices, dtype="<u4").tobytes())
        parts.append(
            dict(
                part,
                vertices=len(pairs),
                indices=len(indices),
                vertex_offset=vertex_offset,
                index_offset=index_offset,
            )
        )
    if not 0 < len(bank) <= 262144:
        raise ValueError("Attribute record count outside runtime limit")
    width = meta["texture_width"]
    if not isinstance(width, int) or width <= 0:
        raise ValueError("Invalid attribute image width")
    height = (len(bank) * 6 + width - 1) // width
    data = b"".join(bank).ljust(width * height * 16, b"\0")
    (out / "data").mkdir()
    attr = blob(out, data, ".f32")
    geom = blob(out, geometry, ".bin")
    model = dict(
        meta,
        format=2,
        attribute_records=len(bank),
        valid_attribute_records=len(bank),
        texture_height=height,
        parts=parts,
        attributes=attr,
        attributes_sha256=attr["sha256"],
        geometry=geom,
        geometry_sha256=geom["sha256"],
    )
    (out / "model.json").write_text(json.dumps(model, ensure_ascii=False, indent=2), encoding="utf8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="mode", required=True)
    u = modes.add_parser("unpack", help="Recover triangle corners from an existing model for comparison")
    u.add_argument("--fragment", type=Path, required=True)
    u.add_argument("--sha", required=True)
    u.add_argument(
        "--data",
        type=Path,
        action="append",
        required=True,
        help="Physical gfl2_rendering/data directory, repeat for shared blobs",
    )
    b = modes.add_parser("bake", help="Write compact draw indices and the six-texel attribute carrier")
    b.add_argument("--matched", type=Path, required=True)
    b.add_argument("--pmx", type=Path, required=True)
    b.add_argument("--profile", type=Path, required=True)
    for mode in (u, b):
        mode.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    with new_directory(args.output) as out:
        (unpack if args.mode == "unpack" else bake)(args, out)
    print(args.output.resolve())


if __name__ == "__main__":
    main()
