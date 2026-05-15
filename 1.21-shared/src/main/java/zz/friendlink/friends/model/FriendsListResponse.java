package zz.friendlink.friends.model;

import java.util.List;

public record FriendsListResponse(
    List<FriendDto> friends,
    List<FriendDto> incomingRequests,
    List<FriendDto> outgoingRequests
) {
    public FriendData toFriendData() {
        return new FriendData(
            friends == null ? List.of() : List.copyOf(friends),
            incomingRequests == null ? List.of() : List.copyOf(incomingRequests),
            outgoingRequests == null ? List.of() : List.copyOf(outgoingRequests)
        );
    }
}
