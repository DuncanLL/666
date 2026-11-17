package aimoh.pmxpreview;

import java.lang.reflect.Field;
import zombie.core.opengl.RenderThread;

/** The game can load this class before or without the optional VP package. */
final class ViewpointMode {
    private static final Field ENABLED = enabledField();

    private static Field enabledField() {
        try {
            return Class.forName("viewpoint.core.View", false, ViewpointMode.class.getClassLoader()).getField("enabled");
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return null;
        }
    }

    static boolean active() {
        try { return ENABLED != null && ENABLED.getBoolean(null); }
        catch (IllegalAccessException unavailable) { return false; }
    }

    static void retire(Runnable close) {
        if (active()) ViewpointModel.retire(close);
        else RenderThread.queueInvokeOnRenderContext(close);
    }
}
