package aimoh.pmxpreview;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import me.zed_0xff.zombie_buddy.Patch;
import zombie.core.Core;
import zombie.ui.UI3DModel;

/** Route the stock 3D character panel to the live player's completed appearance. */
public final class PlayerPreviewPatch {
    private static final ConcurrentHashMap<Object,ReadyRoomPreview.Draw> QUEUED = new ConcurrentHashMap<>();
    private static final class Fields {
        static final Field PANEL=field("this$0"), X=field("absX"), Y=field("absY"), ANGLE=field("animPlayerAngle"), ZOOM=field("zoom");
        private static Field field(String name) {
            try {
                Field field=Class.forName("zombie.ui.UI3DModel$Drawer").getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
        }
    }

    public static void capture(Object drawer) {
        QUEUED.remove(drawer);
        try {
            UI3DModel panel=(UI3DModel)Fields.PANEL.get(drawer);
            ModelSession session=Main.session(panel.getCharacter());
            if(session==null||!session.ready)return;
            ModelSession.Snapshot snapshot=session.snapshot();
            int width=(int)panel.getWidth().doubleValue(),height=(int)panel.getHeight().doubleValue();
            if(snapshot==null||width<16||height<16)return;
            var mesh=session.plainMesh(snapshot.frame());
            QUEUED.put(drawer,new ReadyRoomPreview.Draw(mesh,snapshot.frame(),snapshot.materials(),
                Fields.X.getInt(drawer),Fields.Y.getInt(drawer),width,height,Core.height,
                Fields.ANGLE.getFloat(drawer),Fields.ZOOM.getFloat(drawer)));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            Main.error(failure);
        }
    }

    public static boolean paint(Object drawer) {
        ReadyRoomPreview.Draw draw=QUEUED.remove(drawer);
        return draw!=null&&draw.paintNow();
    }

    public static void release(Object drawer) { QUEUED.remove(drawer); }

    @Patch(className="zombie.ui.UI3DModel$Drawer",methodName="init")
    public static final class Capture {
        @Patch.OnExit public static void exit(@Patch.This Object drawer) { PlayerPreviewPatch.capture(drawer); }
    }

    @Patch(className="zombie.ui.UI3DModel$Drawer",methodName="render")
    public static final class Paint {
        @Patch.OnEnter(skipOn=true) public static boolean enter(@Patch.This Object drawer) { return PlayerPreviewPatch.paint(drawer); }
    }

    @Patch(className="zombie.ui.UI3DModel$Drawer",methodName="postRender")
    public static final class Release {
        @Patch.OnExit public static void exit(@Patch.This Object drawer) { PlayerPreviewPatch.release(drawer); }
    }
}
