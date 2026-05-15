package zz.friendlink.friends.model;

import java.util.Set;
import java.util.UUID;

public record JoinInfoUpdate(String connectToken, Set<UUID> invitedPlayers) {
    public static JoinInfoUpdate emptyInvites() {
        return new JoinInfoUpdate(null, Set.of());
    }
}
