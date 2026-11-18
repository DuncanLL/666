"""Transfer one identified source renderer/material onto target PMX triangle corners.

Both bind meshes must already use the same coordinates and UV convention. See
docs/maintenance/source-material-workflow.md for the NPZ schema and tie policy.
"""

import argparse
import math
from pathlib import Path

import numpy as np


def transfer(source, target, max_distance):
    points = source["triangles"]
    if (
        points.ndim != 3
        or points.shape[1:] != (3, 18)
        or not len(points)
        or not np.isfinite(points).all()
    ):
        raise ValueError("Expected finite source triangles [T,3,18]")
    positions, texcoords, weights = (target[k] for k in ("positions", "uv0", "skin"))
    faces = target["indices"]
    if (
        positions.ndim != 2
        or positions.shape[1] != 3
        or texcoords.shape != (len(positions), 2)
        or weights.shape != (len(positions), 8)
        or faces.ndim != 2
        or faces.shape[1] != 3
        or faces.dtype.kind not in "iu"
        or not faces.size
        or faces.min() < 0
        or faces.max() >= len(positions)
        or not all(np.isfinite(x).all() for x in (positions, texcoords, weights))
    ):
        raise ValueError("Invalid target positions, UVs, weights or triangles")
    # Index source triangles by UV bounding box. Raw UV coordinates can be tiled.
    uv = points[:, :, 14:16].astype("f8")
    low = uv.min(axis=(0, 1))
    extent = np.maximum(uv.max(axis=(0, 1)) - low, 1e-12)
    resolution = 32

    def cell(x):
        return np.clip(np.floor((x - low) * resolution / extent), 0, resolution - 1).astype(int)

    bins = {}
    for index, (a, b) in enumerate(zip(cell(uv.min(axis=1) - 1e-7), cell(uv.max(axis=1) + 1e-7))):
        for u in range(a[0], b[0] + 1):
            for v in range(a[1], b[1] + 1):
                bins.setdefault((u, v), []).append(index)

    def sample(point, coord):
        candidates = np.asarray(bins.get(tuple(cell(coord)), []), dtype=int)
        tri = uv[candidates]
        a, b, c = tri[:, 0], tri[:, 1], tri[:, 2]
        ab, ac = b - a, c - a
        det = ab[:, 0] * ac[:, 1] - ab[:, 1] * ac[:, 0]
        safe = np.where(abs(det) > 1e-15, det, 1.0)
        p = coord - a
        y = (p[:, 0] * ac[:, 1] - p[:, 1] * ac[:, 0]) / safe
        z = (ab[:, 0] * p[:, 1] - ab[:, 1] * p[:, 0]) / safe
        bary = np.column_stack((1 - y - z, y, z))
        keep = (abs(det) > 1e-15) & (bary.min(axis=1) >= -1e-6)
        source_rows = points[candidates[keep]]
        if not len(source_rows):
            raise ValueError("No containing UV triangle")
        rows = np.einsum("ij,ijk->ik", bary[keep], source_rows)
        residual = np.linalg.norm(rows[:, :3] - point, axis=1)
        best = int(residual.argmin())
        if residual[best] > max_distance:
            raise ValueError(f"Bind-position residual {residual[best]:g} exceeds limit")
        winners = rows[residual <= residual[best] + 1e-7]
        if not np.allclose(winners[:, 3:], rows[best, 3:], rtol=0, atol=1e-4):
            raise ValueError("Ambiguous UV seam: separate the source island/triangle candidates")
        row = rows[best]
        if abs(abs(row[9]) - 1) > 1e-4:
            raise ValueError("Inconsistent source tangent handedness")
        normal = row[3:6]
        n = np.linalg.norm(normal)
        if n < 1e-8:
            raise ValueError("Degenerate normal")
        normal = normal / n
        tangent = row[6:9] - normal * np.dot(normal, row[6:9])
        t = np.linalg.norm(tangent)
        if t < 1e-8:
            raise ValueError("Degenerate tangent")
        return normal, tangent / t, np.sign(row[9]), row[10:18]

    out = np.empty((len(faces), 3, 24), dtype="<f4")
    for i, face in enumerate(faces):
        for j, vertex in enumerate(face):
            try:
                normal, tangent, sign, aux = sample(positions[vertex], texcoords[vertex])
            except ValueError as exc:
                raise ValueError(f"Target triangle {i}, corner {j}, vertex {vertex}: {exc}") from exc
            out[i, j] = np.r_[normal, vertex + 1, tangent, sign, aux, weights[vertex]]
    return out


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--target", type=Path, required=True)
    parser.add_argument(
        "--max-distance",
        type=float,
        required=True,
        help="Allowed bind-position residual, in aligned PMX units",
    )
    parser.add_argument("--output", type=Path, required=True, help="New part-N.npy file")
    args = parser.parse_args()
    if not math.isfinite(args.max_distance) or args.max_distance < 0:
        parser.error("Expected a finite nonnegative distance")
    with np.load(args.source, allow_pickle=False) as source, np.load(
        args.target, allow_pickle=False
    ) as target:
        rows = transfer(source, target, args.max_distance)
    with args.output.open("xb") as f:
        np.save(f, rows)
