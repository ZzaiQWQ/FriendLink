package zz.friendlink.util;

import java.util.Locale;
import java.util.UUID;

public final class Uuids {
    private Uuids() {
    }

    public static UUID parseFlexible(String value) {
        String cleaned = value.trim().toLowerCase(Locale.ROOT);
        if (cleaned.length() == 32 && cleaned.chars().allMatch(Uuids::isHex)) {
            cleaned = cleaned.substring(0, 8)
                + "-" + cleaned.substring(8, 12)
                + "-" + cleaned.substring(12, 16)
                + "-" + cleaned.substring(16, 20)
                + "-" + cleaned.substring(20);
        }
        return UUID.fromString(cleaned);
    }

    private static boolean isHex(int character) {
        return (character >= '0' && character <= '9') || (character >= 'a' && character <= 'f');
    }
}
