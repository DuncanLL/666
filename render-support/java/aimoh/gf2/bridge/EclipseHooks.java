package aimoh.gf2.bridge;

import java.util.Map;

/** Standalone Native build. The combined distribution supplies the Eclipse visitor here. */
final class EclipseHooks {
    static final Map<String,Integer> EXPECTED=Map.of();
    static byte[] rewrite(String type,byte[] raw){return raw;}
    private EclipseHooks(){}
}
