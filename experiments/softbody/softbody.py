"""Preview harness for Bullet's upstream soft-body cage and render interpolation."""
from pathlib import Path
import ctypes as C
import json
import os
import sys
import time

ROOT=Path(__file__).resolve().parents[2]
cfg=json.loads((ROOT/'.local/paths.json').read_text('utf8'))
sys.path.insert(0,cfg['python_dependencies'])
import numpy as np

F=C.POINTER(C.c_float)
D=C.POINTER(C.c_double)
def array(value):return np.ascontiguousarray(value,dtype='f4')
def pointer(value):return value.ctypes.data_as(F)


class SoftBody:
    def __init__(self):
        self.dll_directory=os.add_dll_directory(cfg['native_source'])
        self.lib=C.CDLL(str(ROOT/'build/softbody/AimohSoftBodyPreview.dll'))
        self.lib.sb_new.restype=C.c_void_p
        self.lib.sb_delete.argtypes=[C.c_void_p]
        self.lib.sb_add.argtypes=[C.c_void_p,F,F,F,F,F,C.c_int,C.c_int,*([C.c_float]*4)]
        self.lib.sb_frames.argtypes=[C.c_void_p,F,C.c_int]
        self.lib.sb_step.argtypes=[C.c_void_p,C.c_float]
        self.lib.sb_delta.argtypes=[C.c_void_p,C.c_int,F,F]
        self.lib.sb_stats.argtypes=[C.c_void_p,C.c_int,D]
        self.lib.sb_quality.argtypes=[C.c_void_p,C.c_int,D]
        self.lib.sb_impulse.argtypes=[C.c_void_p,C.c_int,F]
        self.lib.sb_press.argtypes=[C.c_void_p,C.c_int,C.c_float]
        self.lib.sb_configure.argtypes=[C.c_void_p,C.c_float,C.c_float,C.c_float]
        self.handle=self.lib.sb_new()
        if not self.handle:raise RuntimeError('Bullet软体预览初始化失败')
        self.outputs=[];self.cpu_ms=0.

    def add(self,center,radius,attachment,positions,normals,*,resolution=96,mass=1.,stiffness=.45,damping=.16,recovery=.035):
        arrays=list(map(array,[center,radius,attachment,positions,normals]))
        result=self.lib.sb_add(self.handle,*[pointer(x) for x in arrays],len(arrays[3]),resolution,mass,stiffness,damping,recovery)
        if result<0:raise RuntimeError('Bullet软体区域初始化失败：'+str(result))
        self.outputs.append((np.empty_like(arrays[3]),np.empty_like(arrays[4])))
        return result

    def frames(self,matrices,reset=False):
        # Bullet and the upstream MMD palette both use column-major matrices.
        matrices=array(matrices)
        result=self.lib.sb_frames(self.handle,pointer(matrices),int(reset))
        if result:raise RuntimeError('软体附着姿态无效：'+str(result))

    def step(self,dt):
        start=time.perf_counter();result=self.lib.sb_step(self.handle,float(dt))
        if result:raise RuntimeError('软体模拟出现非有限状态，预览已停止：'+str(result))
        for i,(p,n) in enumerate(self.outputs):
            if self.lib.sb_delta(self.handle,i,pointer(p),pointer(n))!=len(p):raise RuntimeError('软体变形读取失败')
            if not np.isfinite(p).all() or not np.isfinite(n).all():raise RuntimeError('软体网格出现非有限数值，预览已停止')
        self.cpu_ms=(time.perf_counter()-start)*1000
        return self.outputs

    def impulse(self,id,velocity):
        v=array(velocity)
        if self.lib.sb_impulse(self.handle,id,pointer(v)):raise RuntimeError('软体测试脉冲失败')

    def press(self,id,speed=1.2):
        if self.lib.sb_press(self.handle,id,float(speed)):raise RuntimeError('局部按压测试失败')

    def configure(self,stiffness,damping=.16,recovery=.035):
        if self.lib.sb_configure(self.handle,float(stiffness),float(damping),float(recovery)):
            raise ValueError('软体参数无效')

    def stats(self):
        result=[]
        for i in range(len(self.outputs)):
            a=np.zeros(14,'f8');assert self.lib.sb_stats(self.handle,i,a.ctypes.data_as(D))==0
            quality=np.zeros(2,'f8');assert self.lib.sb_quality(self.handle,i,quality.ctypes.data_as(D))==0
            result.append(dict(nodes=int(a[0]),pins=int(a[1]),volume_ratio=float(a[2]),max_link_strain=float(a[3]),render_vertices=int(a[4]),rest_volume=float(a[5]),recovery=float(a[6]),pose_frame=bool(a[7]),pin_error=float(a[8]),node_error=float(a[9]),max_speed=float(a[10]),active=bool(a[11]),dt=float(a[12]),prediction_calls=int(a[13]),minimum_cell_jacobian=float(quality[0]),inverted_tets=int(quality[1])))
        return result

    def close(self):
        if self.handle:self.lib.sb_delete(self.handle);self.handle=None
        self.dll_directory.close()


def vertex_weights(vertex):
    w=vertex.weight
    values=[1.] if w.type==0 else [w.weights[0],1-w.weights[0]] if w.type==1 else w.weights if w.type==2 else [w.weights.weight,1-w.weights.weight] if w.type==3 else []
    return list(zip(w.bones,values))


def regions(model,bones,unit_scale):
    result=[]
    xyz=np.asarray([v.co for v in model.vertices],dtype='f4')
    normals=np.asarray([v.normal for v in model.vertices],dtype='f4')
    # Match the existing MC-MMD PMX loader exactly: one Z reflection at import.
    xyz[:,2]*=-1;normals[:,2]*=-1
    for bone in bones:
        weights=np.asarray([sum(float(value) for index,value in vertex_weights(v) if index==bone) for v in model.vertices],dtype='f4')
        ids=np.flatnonzero(weights>.001);core=xyz[weights>=.25]
        if len(core)<24:raise ValueError('有效胸部权重不足，不能自动建立软体区域')
        lo,hi=core.min(0),core.max(0)
        center=(lo+hi)*.5;radius=(hi-lo)*.55
        if np.any(radius<1e-4):raise ValueError('胸部绑定区域退化，不能建立软体')
        bone_position=np.asarray(model.bones[bone].location)*np.array([1.,1.,-1.])
        attachment=bone_position-center
        if np.linalg.norm(attachment)<min(radius)*.1:raise ValueError('模型没有明确的胸部附着方向')
        meters=radius*unit_scale
        result.append(dict(bone=int(bone),name=model.bones[bone].name,indices=ids,weights=weights[ids],
                           center=array(center*unit_scale),radius=array(meters),attachment=array(attachment/np.linalg.norm(attachment)),
                           positions=array(xyz[ids]*unit_scale),normals=normals[ids],
                           mass=float(4/3*np.pi*np.prod(meters)*1000)))
    return result
