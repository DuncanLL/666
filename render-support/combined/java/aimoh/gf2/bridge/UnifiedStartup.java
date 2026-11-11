package aimoh.gf2.bridge;

import java.lang.instrument.Instrumentation;
import java.util.HashSet;
import java.util.Set;

/** Entry points wired by the single-MOD packager. */
public final class UnifiedStartup {
    private static final String MODEL_PASS = "viewpoint/render/ModelPass";
    private static volatile Instrumentation pending;
    private static boolean started;

    public static synchronized void start() {
        if (started) return;
        try {
            Class.forName(MODEL_PASS.replace('/', '.'), false, UnifiedStartup.class.getClassLoader());
        } catch (ClassNotFoundException waitingForViewpoint) {
            // ViewpointStartupPatch retries when ZombieBuddy dispatches the host package.
            return;
        } catch (LinkageError failure) {
            System.err.println("[R116] Bundled light startup: " + failure);
            return;
        }
        try {
            VerifiedViewpoint.verify();
            Instrumentation agent = Main.instrumentation();
            Main.installPacks();
            var early = new HashSet<>(RuntimeHooks.EXPECTED.keySet());
            early.remove(MODEL_PASS);
            // VP may read and compile a saved Eclipse pack before the first model pass.
            new RuntimeHooks(early).install(agent);
            pending = agent;
            started = true;
        } catch (Exception | LinkageError failure) {
            System.err.println("[R116] Bundled light startup: " + failure);
        }
    }

    public static void afterCharacterPatches() {
        if (pending != null) installModelPass();
    }

    private static synchronized void installModelPass() {
        Instrumentation agent = pending;
        if (agent == null) return;
        pending = null;
        try {
            // ZombieBuddy has now composed the character's ModelPass advice.
            new RuntimeHooks(Set.of(MODEL_PASS)).install(agent);
            System.out.println("[R116] Bundled Native and Eclipse integration ready");
        } catch (Exception | LinkageError failure) {
            System.err.println("[R116] Bundled ModelPass integration: " + failure);
        }
    }

    private UnifiedStartup() {}
}
