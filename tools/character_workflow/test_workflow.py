"""Asset-free regression examples for the authoring interchange formats."""

import unittest

import numpy as np

from bake_material import material_bindings
from trace_material import buffer_bytes
from transfer_corners import transfer


class WorkflowExamples(unittest.TestCase):
    def test_constant_block(self):
        material = {
            "m_SavedProperties": {
                "m_Floats": [["_Cutoff", 0.25]],
                "m_Colors": [["_Tint", dict(r=0.5, g=1, b=0, a=0.5)]],
                "m_TexEnvs": [["_Map", {"m_Scale": {"x": 2, "y": 3}, "m_Offset": {"x": 0, "y": 1}}]],
            }
        }
        props = {"_Tint": {"type": 0, "value": [1, 1, 1, 1]}}
        layout = [
            dict(name=name, byte_offset=offset, components=count, type=0, array_size=0)
            for name, offset, count in [("_Cutoff", 0, 1), ("_Tint", 16, 4), ("_Map_ST", 32, 4)]
        ]
        values = np.frombuffer(buffer_bytes(48, material_bindings(material, props, layout)), dtype="<f4")
        np.testing.assert_allclose(values, [0.25, 0, 0, 0, 0.21404114, 1, 0, 0.5, 2, 3, 0, 1], rtol=1e-6)

    def test_overlapping_uv_islands_and_seams(self):
        rows = np.zeros((2, 3, 18))
        triangle = [[0, 0], [1, 0], [0, 1]]
        rows[:, :, :2] = triangle
        rows[1, :, 2] = 3
        rows[:, :, 3:6] = [0, 0, 1]
        rows[:, :, 6:10] = [1, 0, 0, -1]
        rows[1, :, 10:14] = [1, 0.5, 0.25, 1]
        rows[:, :, 14:16] = triangle
        rows[1, :, 16:18] = triangle
        positions = np.array([[0.25, 0.25, 3], [0.5, 0.25, 3], [0.25, 0.5, 3]])
        target = dict(
            positions=positions,
            uv0=positions[:, :2],
            indices=np.array([[0, 1, 2]]),
            skin=np.tile([0, 0, 0, 0, 1, 0, 0, 0], (3, 1)),
        )
        result = transfer({"triangles": rows}, target, 1e-6)[0]
        np.testing.assert_array_equal(result[:, 3], [1, 2, 3])
        np.testing.assert_array_equal(result[:, 7], [-1] * 3)
        np.testing.assert_allclose(result[:, 8:12], rows[1, :, 10:14])
        np.testing.assert_allclose(result[:, 14:16], positions[:, :2])
        rows[0, :, 2] = 3
        with self.assertRaisesRegex(ValueError, "Ambiguous UV seam"):
            transfer({"triangles": rows}, target, 1e-6)
        target["uv0"] = np.full((3, 2), 5.0)
        with self.assertRaisesRegex(ValueError, "No containing UV triangle"):
            transfer({"triangles": rows}, target, 1e-6)


if __name__ == "__main__":
    unittest.main()
