package aimoh.pmxpreview;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;

/** Optional ownership handoff for EFZ's first-person weapon placement and stock-model replay. */
public final class EfzAimCompatibility {
    private record Bridge(Method view, Method held, Field replay) {}
    private static final Bridge BRIDGE = find();

    private static Bridge find() {
        String namespace = "pz.escapefromzomboidfirearms.zb.";
        ClassLoader loader = EfzAimCompatibility.class.getClassLoader();
        try {
            Class<?> latch = Class.forName(namespace + "FpViewLatch", false, loader);
            Class<?> camera = Class.forName(namespace + "ViewpointBridge", false, loader);
            Method view = camera.getMethod("state");
            Method held = latch.getDeclaredMethod("held");
            Field replay = latch.getDeclaredField("acceptAllFilter");
            held.setAccessible(true);
            replay.setAccessible(true);
            return new Bridge(view, held, replay);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return null;
        }
    }

    static boolean keepsWeapon(IsoGameCharacter actor) {
        if (BRIDGE == null || actor == null || actor != IsoPlayer.getInstance()) return false;
        try {
            return Integer.valueOf(1).equals(BRIDGE.view.invoke(null)) && Boolean.TRUE.equals(BRIDGE.held.invoke(null));
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return false;
        }
    }

    public static boolean isReplay(Object filter) {
        if (BRIDGE == null || filter == null || !Proxy.isProxyClass(filter.getClass())) return false;
        try {
            return BRIDGE.replay.get(null) == filter;
        } catch (IllegalAccessException | LinkageError unavailable) {
            return false;
        }
    }

    private EfzAimCompatibility() {}
}
