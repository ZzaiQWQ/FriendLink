package zz.friendlink.friends;

import zz.friendlink.i18n.P2PTexts;

public final class OfficialFriendsException extends RuntimeException {
    private final int statusCode;
    private final String operation;

    public OfficialFriendsException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
        this.operation = "request";
    }

    private OfficialFriendsException(String operation, int statusCode, String body) {
        super("FriendLink friends " + operation + " failed with HTTP " + statusCode + ": " + body);
        this.statusCode = statusCode;
        this.operation = operation;
    }

    public static OfficialFriendsException fromHttpStatus(String operation, int statusCode, String body) {
        return new OfficialFriendsException(operation, statusCode, body);
    }

    public String userMessage() {
        return switch (statusCode) {
            case 400 -> P2PTexts.s("error.unknown_profile");
            case 401 -> P2PTexts.s("error.login_rejected");
            case 403 -> P2PTexts.s("error.privacy_forbidden");
            case 429 -> P2PTexts.s("error.rate_limited");
            case 500, 502, 503, 504 -> P2PTexts.s("error.friend_service_unavailable");
            default -> getMessage();
        };
    }
}
