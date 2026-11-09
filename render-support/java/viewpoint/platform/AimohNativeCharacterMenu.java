package viewpoint.platform;

import java.util.Set;

/** Reuse VP's setting rows and persistence, changing only our options' section. */
public final class AimohNativeCharacterMenu {
    private static final Set<String> OPTIONS=Set.of("characterSoftFill","characterDisplayStrength","characterSurfaceDetail","characterSkinGloss","characterOutlineWidth");
    public static String section(PackOption option,String fallback){
        return OPTIONS.contains(option.id)?"Graphics/R116 人物效果":fallback;
    }
}
