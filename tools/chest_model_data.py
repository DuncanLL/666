"""Shared, validated PMX weight fit. Parsing stays with the existing PMX reader."""
import numpy as np

ALIASES={'chestl','chestr','breastl','breastr','bustl','bustr','左胸','右胸','左乳','右乳'}
def array(value):return np.ascontiguousarray(value,dtype='f4')
def vertex_weights(vertex):
    w=vertex.weight
    values=[1.] if w.type==0 else [w.weights[0],1-w.weights[0]] if w.type==1 else w.weights if w.type==2 else [w.weights.weight,1-w.weights.weight] if w.type==3 else []
    return list(zip(w.bones,values))

def inspect(path):
    import pmx_reader
    model=pmx_reader.load(str(path))
    key=lambda name:''.join(c for c in name.casefold() if c.isalnum())
    bones={i for i,b in enumerate(model.bones)if key(b.name)in ALIASES or key(b.name_e)in ALIASES}
    weighted={bone for v in model.vertices for bone,w in vertex_weights(v)if w>1e-6 and bone in bones}
    bodies=[i for i,r in enumerate(model.rigids)if r.bone in weighted and r.mode in [1,2]]
    joints=[j for j in model.joints if j.dest_rigid in bodies]
    usable=bool(bodies)and all(sum(j.dest_rigid==i for j in joints)==1 for i in bodies)
    return model,dict(bones=sorted(weighted),usable=usable)

def regions(model,bones,unit_scale):
    result=[]
    xyz=np.asarray([v.co for v in model.vertices],dtype='f4')
    normals=np.asarray([v.normal for v in model.vertices],dtype='f4')
    # Match the MC-MMD loader: exactly one Z reflection at import.
    xyz[:,2]*=-1;normals[:,2]*=-1
    for bone in bones:
        weights=np.asarray([sum(float(value) for index,value in vertex_weights(v) if index==bone) for v in model.vertices],dtype='f4')
        ids=np.flatnonzero(weights>.001);core=xyz[weights>=.25]
        if len(core)<24:raise ValueError('有效胸部权重不足，不能自动建立软体区域')
        lo,hi=core.min(0),core.max(0);center=(lo+hi)*.5;radius=(hi-lo)*.55
        if np.any(radius<1e-4):raise ValueError('胸部绑定区域退化，不能建立软体')
        bone_position=np.asarray(model.bones[bone].location)*np.array([1.,1.,-1.]);attachment=bone_position-center
        if np.linalg.norm(attachment)<min(radius)*.1:raise ValueError('模型没有明确的胸部附着方向')
        extent=radius*unit_scale
        result.append(dict(bone=int(bone),name=model.bones[bone].name,indices=ids,weights=weights[ids],
            center=array(center*unit_scale),radius=array(extent),attachment=array(attachment/np.linalg.norm(attachment)),
            positions=array(xyz[ids]*unit_scale),normals=normals[ids],mass=float(4/3*np.pi*np.prod(extent)*1000)))
    return result
