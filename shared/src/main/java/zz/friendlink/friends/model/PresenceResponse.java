package zz.friendlink.friends.model;

import java.util.List;

public record PresenceResponse(List<PresenceStatusDto> presence) {
    public static PresenceResponse empty() {
        return new PresenceResponse(List.of());
    }

    public PresenceResponse safeCopy() {
        return new PresenceResponse(presence == null ? List.of() : List.copyOf(presence));
    }
}
