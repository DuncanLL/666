package gfl2.runtime;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import me.zed_0xff.zombie_buddy.Patch;
import zombie.core.Core;
import zombie.core.skinnedmodel.advancedanimation.AnimatedModel;
import zombie.ui.UI3DModel;

/** Route the stock 3D character panel to the live player's completed appearance. */
public final class PlayerPreviewPatch {
    private static final ConcurrentHashMap<Object,ReadyRoomPreview.Draw> QUEUED = new ConcurrentHashMap<>();
    private static final class Fields {
        private static final String DRAWER="zombie.ui.UI3DModel$Drawer";
        static final Field PANEL=field(DRAWER,"this$0"), X=field(DRAWER,"absX"), Y=field(DRAWER,"absY"),
                ANGLE=field(DRAWER,"animPlayerAngle"), ZOOM=field(DRAWER,"zoom");
        static final Field ANIMATED_MODEL=field(UI3DModel.class.getName(),"animatedModel");
        private static Field field(String owner,String name) {
            try {
                Field field=Class.forName(owner).getDeclaredField(name);
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
            boolean isometric=((AnimatedModel)Fields.ANIMATED_MODEL.get(panel)).isIsometric();
            float angle=Fields.ANGLE.getFloat(drawer)+(isometric?(float)(Math.PI/4):0f);
            // Stock projects with reflected X, while the PMX mesh uses reflected Z.
            float yaw=(float)Math.PI-angle;
            float pitch=isometric?(float)(Math.PI/6):0f;
            float baseSize=isometric?22f:25f;
            // UI3DModel subtracts its zoom from the orthographic size. PMX uses a multiplier.
            float zoom=baseSize/Math.max(baseSize/4.2f,baseSize-Fields.ZOOM.getFloat(drawer));
            var mesh=session.plainMesh(snapshot.frame());
            QUEUED.put(drawer,new ReadyRoomPreview.Draw(mesh,snapshot.frame(),snapshot.materials(),
                Fields.X.getInt(drawer),Fields.Y.getInt(drawer),width,height,Core.height,yaw,zoom,0,0,false,null,pitch));
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
