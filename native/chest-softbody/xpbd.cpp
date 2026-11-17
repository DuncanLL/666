// Workbench-only XPBD over the same cage, pins and render embedding as Bullet.
// Compliant constraint projection: Macklin et al., MIG 2016, doi:10.1145/2994258.2994272.
#include <algorithm>
#include "softbody.cpp"

namespace {
struct Edge {
    int a, b;
    btScalar restLength, volume, frontWeight, alpha, lambda;
};

struct Cell {
    std::array<int, 4> ids;
    btScalar inverseSixVolume, compliance, alpha, lambda;
};

struct Constraints {
    std::vector<Edge> edges;
    std::vector<Cell> cells;
    std::vector<btVector3> before;

    explicit Constraints(const Cage& cage) : before(cage.rest.size()) {
        const auto* nodes = &cage.body->m_nodes[0];
        std::map<std::pair<int, int>, btScalar> edgeVolumes;
        for (int t = 0; t < cage.body->m_tetras.size(); ++t) {
            const auto& tetra = cage.body->m_tetras[t];
            std::array<int, 4> ids;
            for (int i = 0; i < 4; ++i) ids[i] = int(tetra.m_n[i] - nodes);
            btScalar volume = tetraMatrix(cage.rest[ids[0]], cage.rest[ids[1]],
                                         cage.rest[ids[2]], cage.rest[ids[3]]).determinant() / 6;
            btScalar absoluteVolume = btFabs(volume);
            if (std::any_of(ids.begin(), ids.end(), [nodes](int i) { return nodes[i].m_im > 0; }))
                cells.push_back({ids, 1 / (6 * volume), 1 / (5000 * absoluteVolume), 0, 0});
            for (int a = 0; a < 4; ++a)
                for (int b = a + 1; b < 4; ++b)
                    edgeVolumes[std::minmax(ids[a], ids[b])] += absoluteVolume;
        }
        for (const auto& [ids, volume] : edgeVolumes) {
            if (nodes[ids.first].m_im == 0 && nodes[ids.second].m_im == 0) continue;
            const auto &a=cage.rest[ids.first], &b=cage.rest[ids.second];
            btScalar length = (a-b).length();
            btVector3 p=(a+b)*.5f-cage.center;
            btVector3 unit(p.x()/cage.extent.x(),p.y()/cage.extent.y(),p.z()/cage.extent.z());
            btScalar front=btClamped(-unit.dot(cage.back),btScalar(0),btScalar(1));
            edges.push_back({ids.first, ids.second, length, volume, front*front, 0, 0});
        }
    }
};

struct XpbdSolver {
    Preview& preview;
    std::vector<Constraints> cages;
    btScalar frontScale=1;

    explicit XpbdSolver(Preview& preview) : preview(preview) {
        cages.reserve(preview.cages.size());
        for (const Cage& cage : preview.cages) cages.emplace_back(cage);
    }
};

btTransform attachmentAt(const Cage& cage, btScalar fraction) {
    return btTransform(cage.previousFrame.getRotation().slerp(cage.frame.getRotation(), fraction),
                       cage.previousFrame.getOrigin().lerp(cage.frame.getOrigin(), fraction));
}

void projectEdge(btSoftBody::tNodeArray& nodes, Edge& edge) {
    auto& a = nodes[edge.a];
    auto& b = nodes[edge.b];
    btVector3 separation = a.m_x - b.m_x;
    btScalar length = separation.length();
    if (length < SIMD_EPSILON) return;
    btScalar update = (edge.restLength - length - edge.alpha * edge.lambda) /
                      (a.m_im + b.m_im + edge.alpha);
    btVector3 correction = separation * (update / length);
    a.m_x += correction * a.m_im;
    b.m_x -= correction * b.m_im;
    edge.lambda += update;
}

void projectCell(btSoftBody::tNodeArray& nodes, Cell& cell) {
    const auto& ids = cell.ids;
    const btVector3& origin = nodes[ids[0]].m_x;
    btVector3 a = nodes[ids[1]].m_x - origin;
    btVector3 b = nodes[ids[2]].m_x - origin;
    btVector3 c = nodes[ids[3]].m_x - origin;
    btVector3 gradients[4];
    gradients[1] = b.cross(c) * cell.inverseSixVolume;
    gradients[2] = c.cross(a) * cell.inverseSixVolume;
    gradients[3] = a.cross(b) * cell.inverseSixVolume;
    gradients[0] = -(gradients[1] + gradients[2] + gradients[3]);
    btScalar denominator = cell.alpha;
    for (int i = 0; i < 4; ++i) denominator += nodes[ids[i]].m_im * gradients[i].length2();
    btScalar update = (1 - a.dot(gradients[1]) - cell.alpha * cell.lambda) / denominator;
    for (int i = 0; i < 4; ++i)
        nodes[ids[i]].m_x += gradients[i] * (nodes[ids[i]].m_im * update);
    cell.lambda += update;
}

void limitMotion(btSoftBody::Node& node, const btVector3& rest, const btTransform& frame,
                 const btTransform& last, const btMatrix3x3& inverseBasis,
                 const btVector3& inverseRadii, btScalar inner, btScalar hardSphere, btScalar dt) {
    btVector3 goal = frame * rest, offset = node.m_x - goal;
    btVector3 normal;
    btScalar resistance = 1;
    if (hardSphere > 0) {
        if (offset.length2() <= hardSphere * hardSphere) return;
        normal = offset.normalized();
        node.m_x = goal + normal * hardSphere;
    } else {
        btVector3 normalized = (inverseBasis * offset) * inverseRadii;
        btScalar lengthSquared = normalized.length2();
        if (lengthSquared <= inner * inner) return;
        btScalar distance = btSqrt(lengthSquared);
        normal = (frame.getBasis() * (normalized * inverseRadii)).normalized();
        if (distance >= 1) {
            node.m_x = goal + offset / distance;
        } else {
            // Only slow outward travel so the material solver can recover freely.
            btScalar t = (distance - inner) / (1 - inner);
            resistance = t * t * (3 - 2 * t) * btMin(btScalar(1), dt * 240);
        }
    }
    btVector3 anchorVelocity = (goal - last * rest) / dt;
    node.m_v -= normal * (resistance * btMax(btScalar(0), (node.m_v - anchorVelocity).dot(normal)));
}
}

extern "C" {
__declspec(dllexport) XpbdSolver* xpbd_create(Preview* preview) {
    return preview && preview->ready && !preview->cages.empty() ? new XpbdSolver(*preview) : nullptr;
}

__declspec(dllexport) void xpbd_destroy(XpbdSolver* solver) { delete solver; }

__declspec(dllexport) int xpbd_step_cached(XpbdSolver* solver, float seconds, float control,
                                          float lateral, float vertical, float depth, float cushion) {
    if (!solver || !std::isfinite(seconds) || seconds < 0 || seconds > .1f ||
        !std::isfinite(control) || control <= 0 || control > 1 ||
        !std::isfinite(cushion) || cushion < 0 || cushion > .6f) return -1;
    for (float axis : {lateral, vertical, depth})
        if (!std::isfinite(axis) || axis < .6f || axis > 1.4f) return -1;
    Preview& preview = solver->preview;
    if (seconds == 0) return sb_step(&preview, 0);
    int steps = int(std::ceil(seconds / (1.f / 240.f)));
    btScalar dt = seconds / steps, inverseDtSquared = 1 / (dt * dt);
    btScalar shear = 50 + 350 * control * control;
    for (std::size_t index = 0; index < preview.cages.size(); ++index) {
        Cage& cage = preview.cages[index];
        Constraints& constraints = solver->cages[index];
        for (Edge& edge : constraints.edges)
            edge.alpha = (edge.restLength * edge.restLength / (shear * edge.volume)) * inverseDtSquared
                        * (1+(solver->frontScale-1)*edge.frontWeight);
        for (Cell& cell : constraints.cells) cell.alpha = cell.compliance * inverseDtSquared;
        auto& nodes = cage.body->m_nodes;
        btScalar decay = std::pow(1 - cage.damping, dt * 60);
        btScalar maximum = .25f * btMin(cage.extent.x(), btMin(cage.extent.y(), cage.extent.z()));
        btVector3 inverseRadii(1 / (maximum * lateral), 1 / (maximum * vertical),
                              1 / (maximum * depth));
        btScalar hardSphere = cushion == 0 && lateral == vertical && vertical == depth ? maximum * lateral : 0;
        // The palette basis may contain scale. Normalize the initial transform in
        // the same way as the later substep poses before deriving anchor velocity.
        btTransform last = attachmentAt(cage, 0);
        for (int step = 1; step <= steps; ++step) {
            btTransform frame = attachmentAt(cage, btScalar(step) / steps);
            btMatrix3x3 inverseBasis = frame.getBasis().transpose();
            for (int i = 0; i < nodes.size(); ++i) {
                auto& node = nodes[i];
                constraints.before[i] = node.m_x;
                if (node.m_im > 0) node.m_x += node.m_v * (dt * decay);
                else node.m_x = frame * cage.rest[i];
            }
            for (Edge& edge : constraints.edges) edge.lambda = 0;
            for (Cell& cell : constraints.cells) cell.lambda = 0;
            for (int iteration = 0; iteration < 4; ++iteration) {
                for (Edge& edge : constraints.edges) projectEdge(nodes, edge);
                for (Cell& cell : constraints.cells) projectCell(nodes, cell);
            }
            for (int i = 0; i < nodes.size(); ++i) {
                auto& node = nodes[i];
                node.m_v.setZero();
                if (node.m_im > 0) {
                    node.m_v = (node.m_x - constraints.before[i]) / dt;
                    limitMotion(node, cage.rest[i], frame, last, inverseBasis, inverseRadii,
                                1 - cushion, hardSphere, dt);
                }
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
