package aimoh.pmxpreview;

import java.util.regex.Pattern;
import me.zed_0xff.zombie_buddy.Patch;

/** Keep the original resolution of the combined weapon skin atlases. */
@Patch(className = "zombie.core.textures.Texture", methodName = "getSharedTextureInternal")
public final class CovenantWeaponTextures {
    private static final String DIRECTORY = "media/textures/GFL2/";
    private static final Pattern ATLAS = Pattern.compile("[a-z][a-z0-9]*Skin[0-9]+\\.png", Pattern.CASE_INSENSITIVE);
    private static final int SIZE_LIMITS = 0x80 | 0x100;

    @Patch.OnEnter
    public static void enter(@Patch.Argument(0) String path, @Patch.Argument(value = 1, readOnly = false) int flags) {
        flags = atlasFlags(path, flags);
    }

    public static int atlasFlags(String path, int flags) {
        if ((flags & SIZE_LIMITS) != 0 && path != null
                && path.regionMatches(true, 0, DIRECTORY, 0, DIRECTORY.length())
                && ATLAS.matcher(path).region(DIRECTORY.length(), path.length()).matches()) {
            return flags & ~SIZE_LIMITS;
        }
        return flags;
    }
}
