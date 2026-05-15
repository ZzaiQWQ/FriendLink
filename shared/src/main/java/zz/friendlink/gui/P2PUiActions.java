package zz.friendlink.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import zz.friendlink.FriendLinkClient;
import zz.friendlink.i18n.P2PTexts;
import zz.friendlink.p2p.ExperimentalP2PSessionManager;
import zz.friendlink.p2p.PeerTargetResolver;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class P2PUiActions {
    private P2PUiActions() {
    }

    public static CompletableFuture<Void> listen(Minecraft client, Consumer<String> statusSink) {
        if (client.getSingleplayerServer() == null) {
            status(client, statusSink, P2PTexts.s("status.listen_needs_world"));
            return CompletableFuture.completedFuture(null);
        }

        status(client, statusSink, P2PTexts.s("status.signaling_connecting"));
        ExperimentalP2PSessionManager manager = FriendLinkClient.experimentalManager(client);
        return manager.connectSignaling()
            .thenCompose(ignored -> manager.publishHostedPresence())
            .orTimeout(35, TimeUnit.SECONDS)
            .whenComplete((presence, throwable) -> client.execute(() -> {
                if (throwable != null) {
                    Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
                    status(client, statusSink, P2PTexts.s("status.listen_failed", cause.getClass().getSimpleName(), cause.getMessage()));
                    return;
                }
                status(client, statusSink, P2PTexts.s("status.listen_success"));
            }))
            .thenApply(ignored -> null);
    }

    public static CompletableFuture<Void> connect(Minecraft client, UUID peerPmid, Consumer<String> statusSink) {
        status(client, statusSink, P2PTexts.s("status.resolving_host", peerPmid));
        ExperimentalP2PSessionManager manager = FriendLinkClient.experimentalManager(client);
        return PeerTargetResolver.resolve(client, peerPmid)
            .thenCompose(resolved -> {
                client.execute(() -> status(client, statusSink, P2PTexts.s("status.target_resolved")));
                return manager.connectSignaling()
                    .orTimeout(20, TimeUnit.SECONDS)
                    .thenCompose(ignored -> {
                        client.execute(() -> status(client, statusSink, P2PTexts.s("status.signaling_connected")));
                        return manager.startOffer(resolved.targetPlayerId());
                    });
            })
            .orTimeout(65, TimeUnit.SECONDS)
            .whenComplete((ignored, throwable) -> client.execute(() -> {
                if (throwable != null) {
                    Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
                    status(client, statusSink, P2PTexts.s("status.join_failed", shortError(cause)));
                    return;
                }
                status(client, statusSink, P2PTexts.s("status.joining_world"));
            }));
    }

    public static void status(Minecraft client, Consumer<String> statusSink, String message) {
        FriendLinkClient.LOGGER.info("[FriendLink UI] {}", message);
        if (statusSink != null) {
            statusSink.accept(message);
        }
        Component component = Component.literal("[FriendLink] " + message);
        client.gui.getChat().addClientSystemMessage(component);
    }

    private static String shortError(Throwable cause) {
        String message = cause.getMessage();
        if (message != null && message.contains("Player not registered with the service")) {
            return P2PTexts.s("error.not_registered");
        }
        if (message != null && message.contains("Message to player could not be delivered")) {
            return P2PTexts.s("error.message_undelivered");
        }
        return cause.getClass().getSimpleName() + ": " + message;
    }
}
