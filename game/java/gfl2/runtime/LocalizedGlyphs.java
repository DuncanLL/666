package gfl2.runtime;

import java.io.FileNotFoundException;
import java.util.Arrays;
import me.zed_0xff.zombie_buddy.Patch;
import zombie.ZomboidFileSystem;
import zombie.core.Translator;
import zombie.core.fonts.AngelCodeFont;
import zombie.ui.TextManager;
import zombie.ui.UIFont;

@Patch(className = "zombie.ui.TextManager", methodName = "Init")
public final class LocalizedGlyphs {
    private static final UIFont[] SIZES = {UIFont.Small, UIFont.Medium, UIFont.Large};

    @Patch.OnExit
    public static void afterInit(@Patch.This TextManager manager) {
        install(manager);
    }

    static void install(TextManager manager) {
        String language = Translator.getLanguage().name();
        for (UIFont size : SIZES) {
            AngelCodeFont font = manager.getFontFromEnum(size);
            if (font == null || font.chars == null) continue;
            String asset = "media/gfl2_pmx/fonts/" + language + "/" + font.getLineHeight() + "/fallback.fnt";
            if (ZomboidFileSystem.instance.getAbsolutePath(asset) == null) continue;
            try {
                appendMissing(font, new AngelCodeFont(asset, ""));
            } catch (FileNotFoundException | RuntimeException failure) {
                System.err.println("[GFL2Exilium] Could not load supplemental glyphs: " + failure);
            }
        }
    }

    static void appendMissing(AngelCodeFont original, AngelCodeFont supplement) {
        var stock = original.chars;
        var combined = Arrays.copyOf(stock, Math.max(stock.length, supplement.chars.length));
        for (var glyph : supplement.chars) {
            if (glyph != null && combined[glyph.id] == null) combined[glyph.id] = glyph;
        }
        // Keep the glyph's original owner so deferred loading can initialize its atlas texture.
        original.chars = combined;
    }
}
