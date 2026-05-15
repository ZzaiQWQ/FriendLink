package zz.friendlink.friends.model;

import java.util.UUID;

public record FriendActionRequest(String name, UUID profileId, UpdateType updateType) {
    public static FriendActionRequest addByName(String name) {
        return new FriendActionRequest(name, null, UpdateType.ADD);
    }

    public static FriendActionRequest addById(UUID profileId) {
        return new FriendActionRequest(null, profileId, UpdateType.ADD);
    }

    public static FriendActionRequest removeById(UUID profileId) {
        return new FriendActionRequest(null, profileId, UpdateType.REMOVE);
    }
}
