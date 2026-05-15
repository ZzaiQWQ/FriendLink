package zz.friendlink.p2p;

import net.minecraft.client.Minecraft;
import zz.friendlink.friends.OfficialFriendsClient;
import zz.friendlink.friends.model.JoinInfoUpdate;
import zz.friendlink.friends.model.PresenceResponse;
import zz.friendlink.friends.model.PresenceStatusDto;

import java.net.ProxySelector;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PeerTargetResolver {
    private PeerTargetResolver() {
    }

    public static CompletableFuture<ResolvedPeer> resolve(Minecraft client, UUID enteredId) {
        String accessToken = client.getUser().getAccessToken();
        return CompletableFuture.supplyAsync(() -> {
            PresenceResponse response = new OfficialFriendsClient(accessToken, ProxySelector.getDefault())
                .presence(HostPresencePublisher.ONLINE, JoinInfoUpdate.emptyInvites());

            for (PresenceStatusDto entry : response.presence()) {
                if (enteredId.equals(entry.pmid())) {
                    return new ResolvedPeer(enteredId, "Using entered presence pmid. status=" + entry.status());
                }
                if (enteredId.equals(entry.profileId()) && entry.pmid() != null) {
                    return new ResolvedPeer(entry.pmid(), "Resolved host profile UUID to presence pmid "
                        + entry.pmid() + ". status=" + entry.status());
                }
            }

            return new ResolvedPeer(enteredId, "No presence entry for " + enteredId
                + "; treating entered id as host pmid. If it is the host UUID, it will not work.");
        });
    }

    public record ResolvedPeer(UUID targetPlayerId, String message) {
    }
}
