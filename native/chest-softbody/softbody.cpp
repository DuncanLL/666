// Preview-only C ABI over pinned Bullet, with the two recorded fixed-node fixes.
#include <cmath>
#include <vector>
#include <array>
#include <map>
#include <atomic>
#include "btBulletDynamicsCommon.h"
#include "BulletSoftBody/btSoftBody.h"
#include "BulletSoftBody/btSoftBodyHelpers.h"
#include "BulletSoftBody/btDeformableMultiBodyDynamicsWorld.h"
#include "BulletSoftBody/btDeformableBodySolver.h"
#include "BulletSoftBody/btDeformableNeoHookeanForce.h"
#include "BulletSoftBody/btDeformableGravityForce.h"
#include "BulletSoftBody/btSoftBodyRigidBodyCollisionConfiguration.h"
#include "BulletSoftBody/btDefaultSoftBodySolver.h"

struct Cage {
    btSoftBody* body = nullptr;
    std::vector<btVector3> rest, skin, normals;
    std::vector<btMatrix3x3> inverseRestTets,normalTransforms;
    std::vector<int> renderTet;
    std::vector<int> pins;
    std::vector<btScalar> inverseMass;
    btVector3 center,extent,back;
    btTransform frame = btTransform::getIdentity();
    btTransform previousFrame = btTransform::getIdentity();
    btScalar damping=0,recovery=0;
    btScalar volume = 0;
    btScalar minimumJacobian=1;
    int invertedTets=0;
};

struct PreviewWorld : btDeformableMultiBodyDynamicsWorld {
    int predictions=0;
    using btDeformableMultiBodyDynamicsWorld::btDeformableMultiBodyDynamicsWorld;
    void predictUnconstraintMotion(btScalar dt) override {
        ++predictions;btDeformableMultiBodyDynamicsWorld::predictUnconstraintMotion(dt);
    }
};
struct PreviewSolver : btDeformableBodySolver {
    // Keep the upstream iteration budget; repeated nonlinear solves dominated this local volume.
    PreviewSolver() { m_maxNewtonIterations=1; m_newtonTolerance=1e-4f; }
};
struct Preview {
    btSoftBodyRigidBodyCollisionConfiguration config;
    btCollisionDispatcher dispatcher{&config};
    btDbvtBroadphase broadphase;
    btDeformableMultiBodyConstraintSolver solver;
    PreviewSolver softSolver;
    PreviewWorld world{&dispatcher, &broadphase, &solver, &config, &softSolver};
    btDeformableNeoHookeanForce elasticity{80,5000,.015f};
    btDeformableGravityForce gravity{btVector3(0,0,0)};
    std::vector<Cage> cages;
    bool ready = false;
    btScalar inertia=1;
    Preview() {
        // The rest shape is already authored. Inertial response is driven by
        // animated attachment motion; no uncalibrated extra gravity sag.
        world.setGravity(btVector3(0,0,0));
        world.getWorldInfo().m_gravity.setValue(0,0,0);
        solver.setDeformableSolver(&softSolver);
        world.setImplicit(true);
        world.setLineSearch(true);
        world.setUseProjection(true);
        world.getWorldInfo().m_sparsesdf.Initialize();
    }
    ~Preview() {
        for (Cage& cage : cages) { world.removeSoftBody(cage.body); delete cage.body; }
        world.getWorldInfo().m_sparsesdf.Reset();
    }
};

// Upstream volumetric elasticity separates shape/shear from bulk resistance.
static void setMaterial(Preview* p,btScalar control,btScalar damping) {
    p->elasticity.setLameParameters(50+350*control*control,5000);
    p->elasticity.setDamping(.003f+.25f*damping);
}

static std::atomic<int> livePreviews{0};
static bool finite3(const float* a) {
    return a && std::isfinite(a[0]) && std::isfinite(a[1]) && std::isfinite(a[2]);
}
static btMatrix3x3 tetraMatrix(const btVector3& a,const btVector3& b,const btVector3& c,const btVector3& d) {
    btVector3 x=b-a,y=c-a,z=d-a;
    return btMatrix3x3(x.x(),y.x(),z.x(), x.y(),y.y(),z.y(), x.z(),y.z(),z.z());
}
extern "C" {
__declspec(dllexport) Preview* sb_new() { auto* p=new Preview(); ++livePreviews; return p; }
__declspec(dllexport) void sb_delete(Preview* p) { if(p) { delete p; --livePreviews; } }
__declspec(dllexport) int sb_live() { return livePreviews.load(); }

// Exercise the exact upstream indexing regression across two bodies with
// interleaved fixed nodes, without depending on an animation or a character.
__declspec(dllexport) int sb_check_fixed_force_index() {
    btSoftBodyWorldInfo info;
    btVector3 points[3]={btVector3(0,0,0),btVector3(1,0,0),btVector3(2,0,0)};
    btScalar masses[3]={1,0,1};
    btSoftBody first(&info,3,points,masses),second(&info,3,points,masses);
    btAlignedObjectArray<btSoftBody*> bodies;bodies.push_back(&first);bodies.push_back(&second);
    btAlignedObjectArray<btVector3> backup,force;backup.resize(6);force.resize(6);
    for(int i=0;i<6;++i) force[i]=btVector3(i+1,0,0);
    for(auto* body:{&first,&second}) for(int i=0;i<3;++i) {
        body->m_nodes[i].m_v.setZero();body->m_nodes[i].m_effectiveMass_inv.setIdentity();
    }
    btDeformableBackwardEulerObjective objective(bodies,backup);objective.setImplicit(true);
    objective.applyForce(force,false);
    return first.m_nodes[0].m_v.x()==1 && first.m_nodes[2].m_v.x()==3
        && second.m_nodes[0].m_v.x()==4 && second.m_nodes[2].m_v.x()==6 ? 0 : -1;
}

__declspec(dllexport) int sb_add(Preview* p, const float* center, const float* radius,
    const float* attachment, const float* xyz, const float* normals, int vertices,
    int resolution, float mass, float stiffness, float damping, float recovery) {
    if(!p || p->ready || !finite3(center) || !finite3(radius) || !finite3(attachment)
       || !xyz || !normals || vertices<1 || resolution<16 || resolution>512
       || !std::isfinite(mass) || !std::isfinite(stiffness) || !std::isfinite(damping) || !std::isfinite(recovery)
       || mass<=0 || stiffness<=0 || stiffness>1
       || damping<0 || damping>=1 || recovery<0 || recovery>1) return -1;
    for(int j=0;j<3;++j) if(radius[j]<=0) return -2;
    Cage cage;
    btVector3 origin(center[0],center[1],center[2]), extent(radius[0],radius[1],radius[2]);
    btVector3 back(attachment[0],attachment[1],attachment[2]);
    if(back.length2()<1e-8f) return -3;
    back.normalize();
    cage.center=origin;cage.extent=extent;cage.back=back;
    cage.body=btSoftBodyHelpers::CreateEllipsoid(p->world.getWorldInfo(), origin, extent, resolution);
    auto* body=cage.body;
    // Animated attachments need a live solver even when their rigid parent is stationary.
    body->setActivationState(DISABLE_DEACTIVATION);
    // One interior node closes the existing ellipsoid as a tetrahedral volume.
    const int middle=body->m_nodes.size();
    body->appendNode(origin,mass/(middle+1));
    body->setTotalMass(mass,false);
    body->m_cfg.kDP=damping;
    body->m_cfg.kMT=0;
    cage.damping=damping;cage.recovery=recovery;
    body->m_cfg.kVC=0;
    body->m_cfg.piterations=0;
    // Upstream position preset: velocity iterations would overwrite its
    // predicted pose-matching correction before the position solve.
    body->m_cfg.viterations=0;
    body->m_cfg.diterations=0;
    // This pilot covers local deformation, not cloth/body/world contacts.
    body->m_cfg.collisions=0;
    for(int i=0;i<body->m_nodes.size();++i) {
        const btVector3 point=body->m_nodes[i].m_x;
        cage.rest.push_back(point);
        btVector3 unit((point.x()-origin.x())/extent.x(),
                      (point.y()-origin.y())/extent.y(),
                      (point.z()-origin.z())/extent.z());
        if(i==middle || unit.dot(back)>.45f) { cage.pins.push_back(i); body->setMass(i,0); }
    }
    if(cage.pins.size()<3 || cage.pins.size()>cage.rest.size()/2) { delete body; return -4; }
    for(int i=0;i<body->m_nodes.size();++i) cage.inverseMass.push_back(body->m_nodes[i].m_im);
    body->setPose(true,true);
    cage.volume=body->getVolume();
    if(!std::isfinite(cage.volume) || btFabs(cage.volume)<1e-9f) { delete body; return -5; }
    body->m_renderNodes.resize(vertices);
    for(int i=0;i<vertices;++i) {
        if(!finite3(xyz+i*3) || !finite3(normals+i*3)) { delete body; return -6; }
        cage.skin.emplace_back(xyz[i*3],xyz[i*3+1],xyz[i*3+2]);
        cage.normals.emplace_back(normals[i*3],normals[i*3+1],normals[i*3+2]);
        body->m_renderNodes[i].m_x=cage.skin.back();
    }
    // A convex cage admits a star tetrahedralization from its interior.
    // Use Bullet's volumetric render interpolation, avoiding long normal-offset
    // extrapolation across the far side of a closed surface.
    for(int i=0;i<body->m_faces.size();++i) {
        const auto& f=body->m_faces[i];
        int a=int(f.m_n[0]-&body->m_nodes[0]), b=int(f.m_n[1]-&body->m_nodes[0]), c=int(f.m_n[2]-&body->m_nodes[0]);
        btScalar volume=tetraMatrix(body->m_nodes[middle].m_x,f.m_n[0]->m_x,f.m_n[1]->m_x,f.m_n[2]->m_x).determinant();
        if(btFabs(volume)>1e-10f) body->appendTetra(middle,a,volume>0?b:c,volume>0?c:b);
    }
    body->initializeDmInverse();
    body->m_tetraScratches.resize(body->m_tetras.size());
    body->m_tetraScratchesTn.resize(body->m_tetras.size());
    btSoftBodyHelpers::interpolateBarycentricWeights(body);
    std::map<std::array<int,4>,int> tetraIndex;
    for(int t=0;t<body->m_tetras.size();++t) {
        const auto& cell=body->m_tetras[t];std::array<int,4> key;
        for(int j=0;j<4;++j) key[j]=int(cell.m_n[j]-&body->m_nodes[0]);
        tetraIndex[key]=t;
        cage.inverseRestTets.push_back(tetraMatrix(cell.m_n[0]->m_x,cell.m_n[1]->m_x,cell.m_n[2]->m_x,cell.m_n[3]->m_x).inverse());
    }
    cage.normalTransforms.resize(cage.inverseRestTets.size());
    for(int i=0;i<vertices;++i) {
        const auto& n=body->m_renderNodesParents[i];std::array<int,4> key;
        for(int j=0;j<4;++j) key[j]=int(n[j]-&body->m_nodes[0]);
        cage.renderTet.push_back(tetraIndex.at(key));
    }
    p->world.addSoftBody(body);
    p->world.addForce(body,&p->elasticity);
    p->world.addForce(body,&p->gravity);
    setMaterial(p,stiffness,damping);
    p->cages.push_back(std::move(cage));
    return int(p->cages.size())-1;
}

__declspec(dllexport) int sb_frames(Preview* p, const float* frames, int reset) {
    if(!p || !frames) return -1;
    for(int k=0;k<int(p->cages.size());++k) {
        Cage& cage=p->cages[k];
        for(int j=0;j<16;++j) if(!std::isfinite(frames[k*16+j])) return -2;
        cage.frame.setFromOpenGLMatrix(frames+k*16);
        if(!p->ready || reset) {
            for(int i=0;i<int(cage.rest.size());++i) {
                auto& node=cage.body->m_nodes[i];
                node.m_x=cage.frame*cage.rest[i]; node.m_q=node.m_x;
                node.m_v.setZero(); node.m_vn.setZero(); node.m_f.setZero();
            }
            cage.body->setPose(true,true);
            cage.body->advanceDeformation();
            // setPose has already captured the complete rest constants. Do not
            // recapture them on the next tick after only the pins have moved.
            cage.body->m_bUpdateRtCst=false;
            cage.previousFrame=cage.frame;
        }
        cage.body->updateBounds();
    }
    p->ready=true;
    return 0;
}

__declspec(dllexport) int sb_step(Preview* p,float dt) {
    if(!p || !p->ready || !std::isfinite(dt) || dt<0 || dt>.1f) return -1;
    if(dt>0) {
        // Local soft volumes use small internal steps. Interpolate their
        // animated attachment instead of teleporting pins to the frame end.
        const int steps=int(std::ceil(dt/(1.f/240.f)));
        const btScalar h=dt/steps;
        for(int step=1;step<=steps;++step) {
            const btScalar fraction=btScalar(step)/steps;
            for(Cage& cage:p->cages) {
                btTransform frame;
                frame.setOrigin(cage.previousFrame.getOrigin().lerp(cage.frame.getOrigin(),fraction));
                frame.setRotation(cage.previousFrame.getRotation().slerp(cage.frame.getRotation(),fraction));
                for(int i:cage.pins) {
                    auto& node=cage.body->m_nodes[i];
                    node.m_x=frame*cage.rest[i];node.m_q=node.m_x;node.m_v.setZero();node.m_f.setZero();
                }
                // The implicit solver carries velocity in m_vn as well as m_v.
                // External damping must update both, otherwise setup restores it.
                const btScalar decay=std::pow(1-cage.damping,h*60);
                for(int i=0;i<cage.body->m_nodes.size();++i) if(cage.body->m_nodes[i].m_im>0) {
                    cage.body->m_nodes[i].m_v*=decay;cage.body->m_nodes[i].m_vn*=decay;
                }
            }
            p->world.stepSimulation(h,0);
            // Standard skinned-soft-body MaxDistance constraint (cf. Jolt's
            // Skinned constraints): bound local freedom without resetting it.
            for(Cage& cage:p->cages) {
                btTransform frame,last;
                frame.setOrigin(cage.previousFrame.getOrigin().lerp(cage.frame.getOrigin(),fraction));
                frame.setRotation(cage.previousFrame.getRotation().slerp(cage.frame.getRotation(),fraction));
                const btScalar before=btScalar(step-1)/steps;
                last.setOrigin(cage.previousFrame.getOrigin().lerp(cage.frame.getOrigin(),before));
                last.setRotation(cage.previousFrame.getRotation().slerp(cage.frame.getRotation(),before));
                const btScalar maximum=.25f*btMin(cage.extent.x(),btMin(cage.extent.y(),cage.extent.z()));
                for(int i=0;i<int(cage.rest.size());++i) {
                    auto& node=cage.body->m_nodes[i];if(node.m_im<=0) continue;
                    const btVector3 goal=frame*cage.rest[i],offset=node.m_x-goal;
                    const btScalar distance=offset.length();
                    if(distance>maximum) {
                        const btVector3 normal=offset/distance;
                        node.m_x=goal+normal*maximum;node.m_q=node.m_x;
                        const btVector3 anchorVelocity=(goal-last*cage.rest[i])/h;
                        node.m_v-=normal*btMax(btScalar(0),(node.m_v-anchorVelocity).dot(normal));
                        node.m_vn=node.m_v;
                    }
                }
            }
        }
        for(Cage& cage:p->cages) cage.previousFrame=cage.frame;
    }
    for(Cage& cage:p->cages) {
        for(int i=0;i<cage.body->m_nodes.size();++i) {
            const btVector3& a=cage.body->m_nodes[i].m_x;
            if(!std::isfinite(a.x()) || !std::isfinite(a.y()) || !std::isfinite(a.z())) return -2;
        }
        cage.body->interpolateRenderMesh();
    }
    return 0;
}

// Return deformation relative to the same animated attachment transform.
__declspec(dllexport) int sb_delta(Preview* p,int id,float* positions,float* normals) {
    if(!p || id<0 || id>=int(p->cages.size()) || !positions || !normals) return -1;
    Cage& cage=p->cages[id];
    cage.minimumJacobian=1;cage.invertedTets=0;
    for(int t=0;t<cage.body->m_tetras.size();++t) {
        const auto& n=cage.body->m_tetras[t].m_n;
        btMatrix3x3 gradient=tetraMatrix(n[0]->m_x,n[1]->m_x,n[2]->m_x,n[3]->m_x)*cage.inverseRestTets[t];
        btScalar determinant=gradient.determinant();
        cage.minimumJacobian=btMin(cage.minimumJacobian,determinant);
        if(determinant<=0) ++cage.invertedTets;
        cage.normalTransforms[t]=gradient.inverse().transpose();
    }
    for(int i=0;i<int(cage.skin.size());++i) {
        btVector3 delta=cage.body->m_renderNodes[i].m_x-cage.frame*cage.skin[i];
        btVector3 softNormal=(cage.normalTransforms[cage.renderTet[i]]*cage.normals[i]).normalized();
        btVector3 normalDelta=softNormal-(cage.frame.getBasis()*cage.normals[i]).normalized();
        for(int j=0;j<3;++j) { positions[i*3+j]=delta[j]; normals[i*3+j]=normalDelta[j]; }
    }
    return int(cage.skin.size());
}

__declspec(dllexport) int sb_impulse(Preview* p,int id,const float* velocity) {
    if(!p || id<0 || id>=int(p->cages.size()) || !finite3(velocity)) return -1;
    auto& nodes=p->cages[id].body->m_nodes;
    for(int i=0;i<nodes.size();++i) if(nodes[i].m_im>0) {
        const btVector3 kick(velocity[0],velocity[1],velocity[2]);
        nodes[i].m_v+=kick;nodes[i].m_vn+=kick;
    }
    return 0;
}

// An explicitly artificial local-pressure impulse for inspecting deformation.
__declspec(dllexport) int sb_press(Preview* p,int id,float speed) {
    if(!p || id<0 || id>=int(p->cages.size()) || !std::isfinite(speed) || speed<0 || speed>3) return -1;
    Cage& cage=p->cages[id];
    btVector3 inward=cage.frame.getBasis()*cage.back;
    for(int i=0;i<int(cage.rest.size());++i) {
        auto& node=cage.body->m_nodes[i];if(node.m_im<=0) continue;
        btVector3 point=cage.rest[i]-cage.center;
        btVector3 unit(point.x()/cage.extent.x(),point.y()/cage.extent.y(),point.z()/cage.extent.z());
        btScalar front=btMax(btScalar(0),-unit.dot(cage.back));
        const btVector3 kick=inward*(speed*front*front);
        node.m_v+=kick;node.m_vn+=kick;
    }
    return 0;
}

__declspec(dllexport) int sb_configure(Preview* p,float stiffness,float damping,float recovery) {
    if(!p || !std::isfinite(stiffness) || !std::isfinite(damping) || !std::isfinite(recovery)
       || stiffness<=0 || stiffness>1 || damping<0 || damping>=1 || recovery<0 || recovery>1) return -1;
    for(Cage& cage:p->cages) {
        cage.damping=damping;cage.recovery=recovery;
    }
    setMaterial(p,stiffness,damping);
    return 0;
}

__declspec(dllexport) int sb_tune(Preview* p,float stiffness,float damping,float inertia) {
    if(!std::isfinite(inertia) || inertia<.6f || inertia>1.4f) return -1;
    if(sb_configure(p,stiffness,damping,0)) return -1;
    if(inertia!=p->inertia) {
        for(Cage& cage:p->cages) for(int i=0;i<cage.body->m_nodes.size();++i)
            cage.body->m_nodes[i].m_im=cage.inverseMass[i]/inertia;
        p->inertia=inertia;
        // Bullet caches inverse masses in its preconditioner, not just nodes.
        if(p->ready) p->softSolver.m_objective->m_preconditioner->reinitialize(true);
    }
    return 0;
}

__declspec(dllexport) int sb_stats(Preview* p,int id,double* out) {
    if(!p || id<0 || id>=int(p->cages.size()) || !out) return -1;
    Cage& cage=p->cages[id];
    out[0]=cage.body->m_nodes.size(); out[1]=cage.pins.size();
    out[2]=double(cage.body->getVolume()/cage.volume);
    double strain=0;
    for(int i=0;i<cage.body->m_links.size();++i) {
        const auto& link=cage.body->m_links[i];
        if(link.m_rl>1e-8f) strain=btMax(strain,double(btFabs((link.m_n[0]->m_x-link.m_n[1]->m_x).length()/link.m_rl-1)));
    }
    out[3]=strain; out[4]=cage.skin.size(); out[5]=cage.volume;
    out[6]=cage.body->m_cfg.kMT; out[7]=cage.body->m_pose.m_bframe;
    double pinError=0,nodeError=0,speed=0;
    for(int i:cage.pins) pinError=btMax(pinError,double((cage.body->m_nodes[i].m_x-cage.frame*cage.rest[i]).length()));
    for(int i=0;i<int(cage.rest.size());++i) {
        nodeError=btMax(nodeError,double((cage.body->m_nodes[i].m_x-cage.frame*cage.rest[i]).length()));
        speed=btMax(speed,double(cage.body->m_nodes[i].m_v.length()));
    }
    out[8]=pinError;out[9]=nodeError;out[10]=speed;out[11]=cage.body->isActive();
    out[12]=cage.body->m_sst.sdt;out[13]=p->world.predictions;
    return 0;
}
__declspec(dllexport) int sb_quality(Preview* p,int id,double* out) {
    if(!p || id<0 || id>=int(p->cages.size()) || !out) return -1;
    out[0]=p->cages[id].minimumJacobian;out[1]=p->cages[id].invertedTets;return 0;
}
}
