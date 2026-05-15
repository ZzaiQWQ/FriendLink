package zz.friendlink.i18n;

import net.minecraft.network.chat.Component;

public final class P2PTexts {
    private static final String PREFIX = "friendlink.";

    private P2PTexts() {
    }

    public static Component c(String key, Object... args) {
        return Component.translatable(PREFIX + key, args);
    }

    public static String s(String key, Object... args) {
        return c(key, args).getString();
    }
}
