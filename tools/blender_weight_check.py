"""Reject armature meshes whose missing weights would silently export on bone zero."""

def inspect_weights(armature, meshes):
    bones={bone.name for bone in armature.data.bones}
    rows=[]
    for mesh in meshes:
        valid={group.index for group in mesh.vertex_groups if group.name in bones}
        missing=[vertex.index for vertex in mesh.data.vertices
                 if not any(group.group in valid and group.weight>1e-8 for group in vertex.groups)]
        rows.append(dict(mesh=mesh.name,vertices=len(mesh.data.vertices),unweighted=len(missing),
                         unweighted_examples=missing[:8]))
    return rows

def require_weights(armature, meshes):
    rows=inspect_weights(armature,meshes)
    failed=[row for row in rows if row['unweighted']]
    if failed:raise ValueError('Unweighted armature vertices would export on bone zero: '+repr(failed))
    return rows
