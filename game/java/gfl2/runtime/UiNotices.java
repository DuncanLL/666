package gfl2.runtime;

import zombie.core.Translator;

final class UiNotices {
    private UiNotices() {}

    static String text(String key) {
        return Translator.getText("UI_GFL2_Notice_" + key);
    }
}
