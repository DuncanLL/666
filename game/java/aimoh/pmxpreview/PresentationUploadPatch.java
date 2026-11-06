package aimoh.pmxpreview;
import me.zed_0xff.zombie_buddy.Patch;
import viewpoint.render.ModelDraws;

/** RC2 copies draw records during interpolation; update its final palette before the existing upload. */
@Patch(className="viewpoint.render.ModelUploads",methodName="put")
public final class PresentationUploadPatch {
    @Patch.OnEnter public static void enter(@Patch.Argument(0) ModelDraws draws){Bridge.presentationHeaders(draws);}
}
