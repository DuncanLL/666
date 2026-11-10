package aimoh.motion;

/** Inertial response to the final, animated torso pose. */
final class JoltChestMotion {
    private boolean hasPosition, hasVelocity;
    private float x, y, z, vx, vz, lateralAcceleration;
    private float strainSpeed, strainAcceleration;
    private float swingSpeed, descent, angle, angularVelocity;
    float kickX, kickZ, strain, swing;

    void update(float x, float y, float z, float sideX, float sideZ, float dt,
                boolean reset, double lateral, double landing, double stretch) {
        kickX = kickZ = strain = 0;
        if (reset || dt > .08f) {
            hasPosition = hasVelocity = false;
            lateralAcceleration = strainSpeed = strainAcceleration = 0;
            swingSpeed = descent = angle = angularVelocity = swing = 0;
            return;
        }
        if (dt <= 0) {
            swing = swingAt(landing);
            return;
        }
        if (hasPosition) {
            float nextX = (x - this.x) / dt, nextY = (y - this.y) / dt, nextZ = (z - this.z) / dt;
            if (nextX * nextX + nextY * nextY + nextZ * nextZ > 64) {
                hasVelocity = false;
                lateralAcceleration = 0;
            } else {
                if (hasVelocity) {
                    float acceleration = ((nextX - vx) * sideX + (nextZ - vz) * sideZ) / dt;
                    lateralAcceleration += smoothing(dt, .055) * (Math.clamp(acceleration, -8, 8) - lateralAcceleration);
                    float kick = (float)(-lateralAcceleration * dt * 1.4 * lateral / 100);
                    kickX = kick * sideX;
                    kickZ = kick * sideZ;
                }
                hasVelocity = true;
                vx = nextX; vz = nextZ;
            }
            if (Math.abs(nextY) <= 8) {
                float change = smoothing(dt, .035) * (nextY - strainSpeed);
                strainSpeed += change;
                strainAcceleration += smoothing(dt, .038) * (change / dt - strainAcceleration);
                strain = (float)((strainAcceleration >= 0 ? 230 : 155) * Math.tanh(strainAcceleration / 4.) * stretch / 100);
                change = smoothing(dt, .033) * (nextY - swingSpeed);
                swingSpeed += change;
                descent = Math.max(-swingSpeed, descent * (float)Math.exp(-dt / .14));
                float activity = Math.clamp((descent - .12f) / .26f, 0, 1);
                float force = 32 * Math.clamp(change / dt, 0, 12) * activity * activity * (3 - 2 * activity);
                int steps = (int)Math.ceil(dt * 240);
                float step = dt / steps, frequency = (float)(2 * Math.PI * 2.25);
                for (int i = 0; i < steps; i++) {
                    angularVelocity += step * (force - frequency * frequency * angle - 1.44f * frequency * angularVelocity);
                    angle += step * angularVelocity;
                }
            } else {
                strainSpeed = strainAcceleration = 0;
                swingSpeed = descent = angle = angularVelocity = 0;
            }
        }
        hasPosition = true;
        this.x = x; this.y = y; this.z = z;
        swing = swingAt(landing);
    }

    private float swingAt(double amount) {
        return (float)Math.clamp(.30 * Math.tanh(angle / .30) * amount / 100, -.42, .42);
    }

    private static float smoothing(float dt, double time) {
        return (float)-Math.expm1(-dt / time);
    }
}
