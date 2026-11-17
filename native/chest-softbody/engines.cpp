// Jolt and MuJoCo solve the same authored cage used by the R116 soft-body bridge.
#include "xpbd.cpp"
#include <Jolt/Jolt.h>
#include <Jolt/RegisterTypes.h>
#include <Jolt/Core/Factory.h>
#include <Jolt/Core/TempAllocator.h>
#include <Jolt/Physics/PhysicsSystem.h>
#include <Jolt/Physics/SoftBody/SoftBodyCreationSettings.h>
#include <Jolt/Physics/SoftBody/SoftBodyMotionProperties.h>
#include <mujoco/mujoco.h>
#include <windows.h>
#include <iomanip>
#include <locale>
#include <memory>
#include <sstream>

namespace {
thread_local std::string engineError;

JPH::Vec3 joltVec(const btVector3& v) { return {v.x(), v.y(), v.z()}; }
btVector3 bulletVec(JPH::Vec3Arg v) { return {v.GetX(), v.GetY(), v.GetZ()}; }

class NoContacts final : public JPH::BroadPhaseLayerInterface,
                         public JPH::ObjectVsBroadPhaseLayerFilter,
                         public JPH::ObjectLayerPairFilter {
public:
    JPH::uint GetNumBroadPhaseLayers() const override { return 1; }
    JPH::BroadPhaseLayer GetBroadPhaseLayer(JPH::ObjectLayer) const override { return JPH::BroadPhaseLayer(0); }
    bool ShouldCollide(JPH::ObjectLayer, JPH::BroadPhaseLayer) const override { return false; }
    bool ShouldCollide(JPH::ObjectLayer, JPH::ObjectLayer) const override { return false; }
};

struct JoltLibrary {
    JoltLibrary() {
        JPH::RegisterDefaultAllocator();
        JPH::Factory::sInstance = new JPH::Factory();
        JPH::RegisterTypes();
    }
    ~JoltLibrary() {
        JPH::UnregisterTypes();
        delete JPH::Factory::sInstance;
        JPH::Factory::sInstance = nullptr;
    }
};

struct JoltWorld {
    NoContacts filter;
    JPH::PhysicsSystem world;
    JoltWorld() {
        static const JoltLibrary library;
        world.Init(8, 0, 8, 8, filter, filter, filter);
        world.SetGravity(JPH::Vec3::sZero());
    }
};

struct CageEngine {
    virtual ~CageEngine() = default;
    virtual bool advance(Cage&, const btTransform&, float dt, float shear, float decay, float signature) = 0;
};

struct JoltRegion final : CageEngine {
    JoltWorld& host;
    JPH::Ref<JPH::SoftBodySharedSettings> settings = new JPH::SoftBodySharedSettings();
    JPH::Body* body = nullptr;
    std::vector<float> edgeFactors;
    std::vector<float> skinWeight;
    JPH::TempAllocatorMalloc temp;

    JoltRegion(JoltWorld& host, const Cage& cage) : host(host) {
        const auto* nodes = &cage.body->m_nodes[0];
        for (int i = 0; i < cage.body->m_nodes.size(); ++i) {
            const auto& p = cage.rest[i];
            settings->mVertices.emplace_back(JPH::Float3(p.x(), p.y(), p.z()), JPH::Float3(0, 0, 0), nodes[i].m_im);
            btVector3 d=p-cage.center;
            btVector3 unit(d.x()/cage.extent.x(),d.y()/cage.extent.y(),d.z()/cage.extent.z());
            skinWeight.push_back(btClamped((unit.dot(cage.back)+.35f)/.80f,0.f,1.f));
            if(nodes[i].m_im>0&&skinWeight.back()>0){
                settings->mSkinnedConstraints.emplace_back(i,1.f,FLT_MAX,0.f);
                settings->mSkinnedConstraints.back().mWeights[0]=JPH::SoftBodySharedSettings::SkinWeight(0,1.f);
            }
        }
        for (int i = 0; i < cage.body->m_faces.size(); ++i) {
            const auto& f = cage.body->m_faces[i];
            settings->mFaces.emplace_back(unsigned(f.m_n[0] - nodes), unsigned(f.m_n[1] - nodes), unsigned(f.m_n[2] - nodes));
        }
        Constraints topology(cage);
        for (const auto& edge : topology.edges)
            settings->mEdgeConstraints.emplace_back(edge.a, edge.b, 0);
        for (const auto& cell : topology.cells) {
            const auto& v = cell.ids;
            float volumeScale = 1 / cell.inverseSixVolume;
            settings->mVolumeConstraints.emplace_back(v[0], v[1], v[2], v[3], cell.compliance * volumeScale * volumeScale);
        }
        settings->CalculateEdgeLengths();
        settings->CalculateVolumeConstraintVolumes();
        settings->mInvBindMatrices.emplace_back(0,JPH::Mat44::sIdentity());
        settings->CalculateSkinnedConstraintNormals();
        settings->Optimize();
        // Optimize reorders constraints. Recover the material factor by the vertex pair.
        for (const auto& edge : settings->mEdgeConstraints) {
            auto found = std::find_if(topology.edges.begin(), topology.edges.end(), [&](const Edge& source) {
                return (unsigned(source.a) == edge.mVertex[0] && unsigned(source.b) == edge.mVertex[1]) ||
                       (unsigned(source.b) == edge.mVertex[0] && unsigned(source.a) == edge.mVertex[1]);
            });
            edgeFactors.push_back(found->restLength * found->restLength / found->volume);
        }
        JPH::SoftBodyCreationSettings create(settings, JPH::RVec3::sZero(), JPH::Quat::sIdentity(), 0);
        create.mUpdatePosition = false;
        create.mLinearDamping = 0;
        create.mNumIterations = 4;
        body = host.world.GetBodyInterface().CreateSoftBody(create);
    }
    ~JoltRegion() override {
        if (body) host.world.GetBodyInterface().DestroyBody(body->GetID());
    }
    bool advance(Cage& cage, const btTransform& frame, float dt, float shear, float decay, float signature) override {
        if (!body) return false;
        for (std::size_t i = 0; i < edgeFactors.size(); ++i)
            settings->mEdgeConstraints[i].mCompliance = edgeFactors[i] / shear;
        auto* motion = static_cast<JPH::SoftBodyMotionProperties*>(body->GetMotionProperties());
        bool skinned=signature>0;
        motion->SetEnableSkinConstraints(skinned);
        if(skinned){
            float maximum=.25f*btMin(cage.extent.x(),btMin(cage.extent.y(),cage.extent.z()));
            for(auto& skin:settings->mSkinnedConstraints)
                skin.mMaxDistance=maximum*(1.55f-1.46f*skinWeight[skin.mVertex]*(signature*.01f));
            auto& basis=frame.getBasis();
            auto column=[&](int i){
                auto p=basis.getColumn(i);return JPH::Vec4(p.x(),p.y(),p.z(),0);
            };
            JPH::Mat44 matrix(column(0),column(1),column(2),joltVec(frame.getOrigin()));
            motion->SkinVertices(body->GetCenterOfMassTransform(),&matrix,1,false,temp);
        }
        auto& vertices = motion->GetVertices();
        auto& nodes = cage.body->m_nodes;
        for (int i = 0; i < nodes.size(); ++i) {
            auto& v = vertices[i];
            v.mInvMass = nodes[i].m_im;
            v.mPosition = v.mPreviousPosition = joltVec(v.mInvMass > 0 ? nodes[i].m_x : frame * cage.rest[i]);
            v.mVelocity = v.mInvMass > 0 ? joltVec(nodes[i].m_v * decay) : JPH::Vec3::sZero();
        }
        motion->CustomUpdate(dt, *body, host.world);
        for (int i = 0; i < nodes.size(); ++i) {
            nodes[i].m_x = bulletVec(vertices[i].mPosition);
            nodes[i].m_v = bulletVec(vertices[i].mVelocity);
        }
        return true;
    }
};

const char engineModuleAnchor = 0;

struct MujocoLibrary {
    HMODULE dll = nullptr;
    decltype(&mj_defaultVFS) defaultVFS = nullptr;
    decltype(&mj_addBufferVFS) addBuffer = nullptr;
    decltype(&mj_deleteVFS) deleteVFS = nullptr;
    decltype(&mj_loadXML) loadXML = nullptr;
    decltype(&mj_makeData) makeData = nullptr;
    decltype(&mj_deleteData) deleteData = nullptr;
    decltype(&mj_deleteModel) deleteModel = nullptr;
    decltype(&mj_step) step = nullptr;

    template<class T> void load(T& fn, const char* name) { fn = reinterpret_cast<T>(GetProcAddress(dll, name)); }
    MujocoLibrary() {
        HMODULE module = nullptr;
        wchar_t path[MAX_PATH];
        if (!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                               reinterpret_cast<LPCWSTR>(&engineModuleAnchor), &module))
            return;
        auto size = GetModuleFileNameW(module, path, MAX_PATH);
        if (size == 0 || size == MAX_PATH) return;
        std::wstring filename(path, size);
        filename.resize(filename.find_last_of(L"\\/") + 1);
        filename += L"mujoco.dll";
        dll = LoadLibraryExW(filename.c_str(), nullptr, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
        if (!dll) return;
        decltype(&mj_version) version = nullptr;
        load(version, "mj_version");
        if (!version || version() != mjVERSION_HEADER) return;
        load(defaultVFS, "mj_defaultVFS"); load(addBuffer, "mj_addBufferVFS");
        load(deleteVFS, "mj_deleteVFS"); load(loadXML, "mj_loadXML");
        load(makeData, "mj_makeData"); load(deleteData, "mj_deleteData");
        load(deleteModel, "mj_deleteModel"); load(step, "mj_step");
    }
    ~MujocoLibrary() { if (dll) FreeLibrary(dll); }
    bool ready() const { return defaultVFS && addBuffer && deleteVFS && loadXML && makeData && deleteData && deleteModel && step; }
};

struct MujocoRegion final : CageEngine {
    static constexpr float baseShear = 120.875f;
    const MujocoLibrary& library;
    mjModel* model = nullptr;
    mjData* data = nullptr;
    struct Vertex { int position = -1, velocity = -1; };
    std::vector<Vertex> vertices;
    std::vector<mjtNum> stiffness;
    std::vector<mjtNum> shearStiffness;
    float previousFactor = 0;
    float previousSignature = -1;

    MujocoRegion(const MujocoLibrary& library, const Cage& cage) : library(library) {
        if (!library.ready()) { engineError = "MuJoCo 3.17.0 DLL/API unavailable"; return; }
        std::ostringstream xml;
        xml.imbue(std::locale::classic());
        xml << std::setprecision(9)
            << "<mujoco><option gravity='0 0 0' integrator='discrete'>"
               "<flag contact='disable' warmstart='disable'/></option><worldbody>"
               "<flexcomp name='r116' type='direct' dim='3' mass='";
        float mass = 0;
        for (int i = 0; i < cage.body->m_nodes.size(); ++i)
            if (cage.inverseMass[i] > 0) mass += 1 / cage.inverseMass[i];
        xml << mass * cage.rest.size() / (cage.rest.size() - cage.pins.size()) << "' point='";
        for (const auto& v : cage.rest) xml << v.x() << ' ' << v.y() << ' ' << v.z() << ' ';
        xml << "' element='";
        const auto* nodes = &cage.body->m_nodes[0];
        for (int i = 0; i < cage.body->m_tetras.size(); ++i)
            for (const auto* node : cage.body->m_tetras[i].m_n) xml << (node - nodes) << ' ';
        xml << "'>";
        std::string header=xml.str();
        std::ostringstream footer;
        footer<<"<pin id='";
        for (int id : cage.pins) footer << id << ' ';
        footer << "'/></flexcomp></worldbody></mujoco>";
        auto compile=[&](float poisson){
            std::ostringstream text;
            text.imbue(std::locale::classic());
            text<<std::setprecision(9)<<header<<"<elasticity young='"<<2*baseShear*(1+poisson)
                <<"' poisson='"<<poisson<<"'/>"<<footer.str();
            return compileModel(text.str());
        };
        model=compile(.47f);
        if (!model) return;
        if (model->nflex != 1 || std::size_t(model->nflexvert) != cage.rest.size() || model->nflexstiffness == 0) {
            engineError = "MuJoCo flex topology mismatch"; return;
        }
        mjModel* shearModel=compile(0);
        if(!shearModel)return;
        if(shearModel->nflexstiffness!=model->nflexstiffness){
            library.deleteModel(shearModel);engineError="MuJoCo material basis mismatch";return;
        }
        shearStiffness.assign(shearModel->flex_stiffness,shearModel->flex_stiffness+shearModel->nflexstiffness);
        library.deleteModel(shearModel);
        vertices.resize(cage.rest.size());
        for (std::size_t i = 0; i < vertices.size(); ++i) {
            if (cage.inverseMass[i] == 0) continue;
            int body = model->flex_vertbodyid[i];
            if (model->body_jntnum[body] != 3) { engineError = "MuJoCo flex vertex is not translational"; return; }
            int joint = model->body_jntadr[body];
            for (int axis = 0; axis < 3; ++axis)
                if (model->jnt_type[joint + axis] != mjJNT_SLIDE || model->jnt_axis[(joint + axis) * 3 + axis] != 1) {
                    engineError = "Unexpected MuJoCo flex axes"; return;
                }
            vertices[i] = {model->jnt_qposadr[joint], model->jnt_dofadr[joint]};
        }
        stiffness.assign(model->flex_stiffness, model->flex_stiffness + model->nflexstiffness);
        data = library.makeData(model);
        if (!data) engineError = "MuJoCo flex allocation failed";
    }
    mjModel* compileModel(const std::string& source) {
        mjVFS vfs;
        library.defaultVFS(&vfs);
        char error[1024]{};
        mjModel* compiled=nullptr;
        if (library.addBuffer(&vfs, "r116.xml", source.data(), int(source.size())) == 0)
            compiled = library.loadXML("r116.xml", &vfs, error, sizeof(error));
        library.deleteVFS(&vfs);
        if(!compiled)engineError=error[0]?error:"MuJoCo flex could not be compiled";
        return compiled;
    }
    ~MujocoRegion() override {
        if (data) library.deleteData(data);
        if (model) library.deleteModel(model);
    }
    bool advance(Cage& cage, const btTransform& frame, float dt, float shear, float decay, float signature) override {
        if (!data) return false;
        model->opt.timestep = dt;
        auto& nodes = cage.body->m_nodes;
        float inertia = 1;
        for (int i = 0; i < nodes.size(); ++i)
            if (nodes[i].m_im > 0) { inertia = cage.inverseMass[i] / nodes[i].m_im; break; }
        // With zero gravity and fixed pins, scaling stiffness by inverse mass
        // ratio produces the same free response as scaling all particle masses.
        float factor = shear / (baseShear * inertia);
        if (factor != previousFactor || signature!=previousSignature) {
            double nu=signature<=50 ? .20+signature*.0054 : .47+(signature-50)*.0004;
            double bulkRatio=(nu/(1-2*nu))/(.47/(1-2*.47));
            // At fixed shear modulus the tetrahedral stiffness is linear in
            // Lamé lambda. Use two compiled material bases without resetting qpos.
            for (std::size_t i = 0; i < stiffness.size(); ++i){
                auto k=signature==50?stiffness[i]:shearStiffness[i]+bulkRatio*(stiffness[i]-shearStiffness[i]);
                model->flex_stiffness[i] = k * factor;
            }
            previousFactor = factor;
            previousSignature=signature;
        }
        btTransform inverse = frame.inverse();
        for (int i = 0; i < nodes.size(); ++i) {
            const auto v = vertices[i];
            if (v.position < 0) continue;
            // Rebase in the moving pin frame while preserving world inertia.
            btVector3 position = inverse * nodes[i].m_x - cage.rest[i];
            btVector3 velocity = inverse.getBasis() * (nodes[i].m_v * decay);
            for (int axis = 0; axis < 3; ++axis) {
                data->qpos[v.position + axis] = position[axis];
                data->qvel[v.velocity + axis] = velocity[axis];
            }
        }
        auto previousWarnings = data->warning[mjWARN_BADQACC].number + data->warning[mjWARN_BADQPOS].number
                                + data->warning[mjWARN_BADQVEL].number;
        library.step(model, data);
        if (previousWarnings != data->warning[mjWARN_BADQACC].number + data->warning[mjWARN_BADQPOS].number
                                + data->warning[mjWARN_BADQVEL].number) return false;
        for (int i = 0; i < nodes.size(); ++i) {
            const auto v = vertices[i];
            btVector3 position = cage.rest[i], velocity(0, 0, 0);
            if (v.position >= 0)
                for (int axis = 0; axis < 3; ++axis) {
                    position[axis] += float(data->qpos[v.position + axis]);
                    velocity[axis] = float(data->qvel[v.velocity + axis]);
                }
            nodes[i].m_x = frame * position;
            nodes[i].m_v = frame.getBasis() * velocity;
        }
        return true;
    }
};

struct Backend {
    Preview& preview;
    std::unique_ptr<JoltWorld> jolt;
    std::vector<std::unique_ptr<CageEngine>> cages;
    float signature=0;
    float strainDrive=0;
};

void driveStrain(Cage& cage, const btMatrix3x3& basis, float strength) {
    auto& nodes = cage.body->m_nodes;
    btVector3 center(0, 0, 0);
    float mass = 0;
    for (int i = 0; i < nodes.size(); ++i) {
        const auto& node = nodes[i];
        if (node.m_im <= 0) continue;
        center += node.m_x / node.m_im;
        mass += 1 / node.m_im;
    }
    if (mass <= 0) return;
    center /= mass;
    const auto inverse = basis.transpose();
    for (int i = 0; i < nodes.size(); ++i) {
        auto& node = nodes[i];
        if (node.m_im <= 0) continue;
        const btVector3 offset = inverse * (node.m_x - center);
        // Balance longitudinal compression with expansion across the section.
        node.m_v += basis * btVector3(.8f * offset.x(), -offset.y(), .2f * offset.z()) * strength;
    }
}
}

extern "C" {
__declspec(dllexport) const char* backend_error() { return engineError.c_str(); }

__declspec(dllexport) Backend* backend_create(Preview* preview, int engine) {
    engineError.clear();
    if (!preview || !preview->ready || (engine != 2 && engine != 3)) return nullptr;
    auto result = std::make_unique<Backend>(Backend{*preview, {}, {}});
    result->signature=engine==2?0.f:50.f;
    if (engine == 2) result->jolt = std::make_unique<JoltWorld>();
    for (const Cage& cage : preview->cages) {
        if (engine == 2) {
            auto region = std::make_unique<JoltRegion>(*result->jolt, cage);
            if (!region->body) { engineError = "Jolt soft body allocation failed"; return nullptr; }
            result->cages.push_back(std::move(region));
        } else {
            static const MujocoLibrary mujoco;
            auto region = std::make_unique<MujocoRegion>(mujoco, cage);
            if (!region->data) return nullptr;
            result->cages.push_back(std::move(region));
        }
    }
    return result.release();
}

__declspec(dllexport) void backend_destroy(Backend* backend) { delete backend; }

__declspec(dllexport) int jolt_set_strain_drive(Backend* backend, float value) {
    if (!backend || !backend->jolt || !std::isfinite(value) || std::abs(value) > 480.f) return -1;
    backend->strainDrive = value;
    return 0;
}

__declspec(dllexport) int sb_set_signature(Preview* world, void* solver, int engine, float value) {
    if(!world||!std::isfinite(value)||value<0||value>100)return -1;
    if(engine==0){world->elasticity.setDamping(.01425f*(value*.02f));return 0;}
    if(!solver)return -1;
    if(engine==1){static_cast<XpbdSolver*>(solver)->frontScale=std::exp2((value-50)*.04f);return 0;}
    if(engine==2||engine==3){static_cast<Backend*>(solver)->signature=value;return 0;}
    return -1;
}

__declspec(dllexport) int backend_step(Backend* backend, float seconds, float control,
                                       float lateral, float vertical, float depth, float cushion) {
    if (!backend || !std::isfinite(seconds) || seconds < 0 || seconds > .1f ||
        !std::isfinite(control) || control <= 0 || control > 1 ||
        !std::isfinite(cushion) || cushion < 0 || cushion > .6f) return -1;
    for (float axis : {lateral, vertical, depth})
        if (!std::isfinite(axis) || axis < .6f || axis > 1.4f) return -1;
    auto& preview = backend->preview;
    if (seconds == 0) return sb_step(&preview, 0);
    int steps = int(std::ceil(seconds / (1.f / 240.f)));
    float dt = seconds / steps, shear = 50 + 350 * control * control;
    for (std::size_t i = 0; i < preview.cages.size(); ++i) {
        Cage& cage = preview.cages[i];
        float maximum = .25f * btMin(cage.extent.x(), btMin(cage.extent.y(), cage.extent.z()));
        btVector3 inverseRadii(1 / (maximum * lateral), 1 / (maximum * vertical), 1 / (maximum * depth));
        auto last = attachmentAt(cage, 0);
        float decay = std::pow(1 - cage.damping, dt * 60);
        for (int step = 1; step <= steps; ++step) {
            auto frame = attachmentAt(cage, float(step) / steps);
            if (backend->strainDrive != 0)
                driveStrain(cage, frame.getBasis(), backend->strainDrive * dt);
            if (!backend->cages[i]->advance(cage, frame, dt, shear, decay,backend->signature)) return -2;
            auto inverseBasis = frame.getBasis().transpose();
            for (int nodeIndex = 0; nodeIndex < cage.body->m_nodes.size(); ++nodeIndex) {
                auto& node = cage.body->m_nodes[nodeIndex];
                if (node.m_im > 0)
                    limitMotion(node, cage.rest[nodeIndex], frame, last, inverseBasis, inverseRadii, 1 - cushion, 0, dt);
                node.m_q = node.m_x;
                node.m_vn = node.m_v;
            }
            last = frame;
        }
        cage.previousFrame = cage.frame;
    }
    return sb_step(&preview, 0);
}
}
