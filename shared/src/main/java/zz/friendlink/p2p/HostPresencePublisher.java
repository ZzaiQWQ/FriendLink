package zz.friendlink.p2p;

import net.minecraft.client.Minecraft;
import zz.friendlink.friends.OfficialFriendsClient;
import zz.friendlink.friends.model.JoinInfoUpdate;
import zz.friendlink.friends.model.PresenceResponse;

import java.net.ProxySelector;
import java.util.concurrent.CompletableFuture;

public final class HostPresencePublisher {
    public static final String ONLINE = "ONLINE";
    public static final String PLAYING_OFFLINE = "PLAYING_OFFLINE";
    public static final String PLAYING_HOSTED_SERVER = "PLAYING_HOSTED_SERVER";

    private HostPresencePublisher() {
    }

    public static CompletableFuture<PresenceResponse> postHosted(Minecraft client) {
        return post(client, PLAYING_HOSTED_SERVER);
    }

    public static CompletableFuture<PresenceResponse> postCurrent(Minecraft client) {
        return post(client, currentStatus(client));
    }

    public static String currentStatus(Minecraft client) {
        if (client.getSingleplayerServer() != null) {
            return PLAYING_HOSTED_SERVER;
        }
        if (client.level != null) {
            return ONLINE;
        }
        return ONLINE;
    }

    private static CompletableFuture<PresenceResponse> post(Minecraft client, String status) {
        String accessToken = client.getUser().getAccessToken();
        return CompletableFuture.supplyAsync(() -> new OfficialFriendsClient(accessToken, ProxySelector.getDefault())
            .presence(status, JoinInfoUpdate.emptyInvites()));
    }
}
