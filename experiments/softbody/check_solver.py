"""Bounded stability test of the upstream solver before model integration."""
from pathlib import Path
import json
import numpy as np
from softbody import SoftBody,array

root=Path(__file__).resolve().parents[2]
angles=np.arange(120)*np.pi*(3-np.sqrt(5));y=1-2*(np.arange(120)+.5)/120
s=np.sqrt(1-y*y);unit=np.column_stack((np.cos(angles)*s,y,np.sin(angles)*s)).astype('f4')
radius=array([.09,.085,.10]);points=unit*radius
body=SoftBody()
try:
    assert body.lib.sb_check_fixed_force_index()==0,'Fixed-node force indexing regression'
    body.add([0,0,0],radius,[0,0,1],points,unit,mass=2.5)
    matrix=np.eye(4,dtype='f4').T.ravel()[None]
    body.frames(matrix,True);rest=body.step(0)[0][0].copy()
    assert np.max(abs(rest))<1e-5
    trace=[];times=[];volumes=[]
    for i in range(720):
        if i==60:body.impulse(0,[.30,.10,-.15])
        body.frames(matrix)
        delta=body.step(1/60)[0][0]
        trace.append(float(np.linalg.norm(delta,axis=1).max()));times.append(body.cpu_ms);volumes.append(body.stats()[0]['volume_ratio'])
    report=dict(initial_delta_max=float(abs(rest).max()),peak_deformation_m=max(trace),final_deformation_m=trace[-1],
                volume_min=min(volumes),volume_max=max(volumes),cpu_median_ms=float(np.median(times)),cpu_p95_ms=float(np.percentile(times,95)),stats=body.stats())
    print(json.dumps(report),flush=True)
    body.configure(.18)
    try:body.configure(float('nan'))
    except ValueError:pass
    else:raise AssertionError('Non-finite material parameters were accepted')
    body.configure(.45)
    report['runtime_material_update_and_nonfinite_rejection_checked']=True
    report['interleaved_fixed_nodes_force_indexing_checked']=True
    report['length_units']='existing_source_skeleton_units_not_SI_metres'
    report['recovery_target_met']=report['final_deformation_m']<.001
    report['status']='COMPONENT_CHECKS_PASSED' if report['recovery_target_met'] else 'RECOVERY_TARGET_NOT_MET'
finally:body.close()
assert body.lib.sb_live()==0
(root/'build/softbody/solver-check.json').write_text(json.dumps(report,indent=2),'utf8')
print(json.dumps(report),flush=True)
assert report['peak_deformation_m']>.002
assert .75<min(volumes)<1.25 and .75<max(volumes)<1.25
assert report['recovery_target_met'],'Impulse recovery residual exceeds the recorded target; preview is not fully accepted'
