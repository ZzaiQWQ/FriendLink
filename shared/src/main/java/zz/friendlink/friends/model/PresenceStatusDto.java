package zz.friendlink.friends.model;

import java.time.Instant;
import java.util.UUID;

public record PresenceStatusDto(
    UUID profileId,
    UUID pmid,
    String status,
    JoinInfo joinInfo,
    Instant lastSeen
) {
    public record JoinInfo(String connectToken, boolean invited) {
    }
}
