package zz.friendlink;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zz.friendlink.diagnostics.P2PDiagnostics;
import zz.friendlink.diagnostics.P2PStatus;
import zz.friendlink.friends.OfficialFriendsClient;
import zz.friendlink.friends.OfficialFriendsException;
import zz.friendlink.friends.model.FriendData;
import zz.friendlink.friends.model.JoinInfoUpdate;
import zz.friendlink.friends.model.PresenceResponse;
import zz.friendlink.p2p.ExperimentalP2PSessionManager;
import zz.friendlink.p2p.HostPresencePublisher;
import zz.friendlink.p2p.PeerTargetResolver;
import zz.friendlink.signaling.SignalingServiceClient;
import zz.friendlink.util.Uuids;

import java.net.ProxySelector;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class FriendLinkClient implements ClientModInitializer {
    public static final String MOD_ID = "friendlink";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static ExperimentalP2PSessionManager experimentalP2P;

    @Override
    public void onInitializeClient() {
        LOGGER.info("FriendLink loaded");
        registerCommands();
    }

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            ClientCommands.literal("friendlink")
                .then(ClientCommands.literal("id").executes(context -> {
                    Minecraft minecraft = Minecraft.getInstance();
                    context.getSource().sendFeedback(Component.literal("[FriendLink] player="
                        + minecraft.getUser().getName() + " uuid=" + minecraft.getUser().getProfileId()));
                    return 1;
                }))
                .then(ClientCommands.literal("status").executes(context -> {
                    Minecraft minecraft = Minecraft.getInstance();
                    P2PStatus status = P2PDiagnostics.collect(minecraft);
                    status.toChatLines().forEach(line -> context.getSource().sendFeedback(Component.literal(line)));
                    status.log(LOGGER);
                    return 1;
                }))
                .then(ClientCommands.literal("friends").executes(context -> {
                    Minecraft minecraft = Minecraft.getInstance();
                    User user = minecraft.getUser();
                    context.getSource().sendFeedback(Component.literal("[FriendLink] fetching official friends..."));

                    CompletableFuture
                        .supplyAsync(() -> new OfficialFriendsClient(user.getAccessToken(), ProxySelector.getDefault()).getFriendData())
                        .whenComplete((friendData, throwable) -> minecraft.execute(() -> {
                            if (throwable != null) {
                                Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
                                String message = cause instanceof OfficialFriendsException friendsException
                                    ? friendsException.userMessage()
                                    : cause.getClass().getSimpleName() + ": " + cause.getMessage();
                                context.getSource().sendFeedback(Component.literal("[FriendLink] friends failed: " + message));
                                LOGGER.warn("FriendLink friends request failed", cause);
                                return;
                            }

                            sendFriendData(context.getSource()::sendFeedback, friendData);
                        }));
                    return 1;
                }))
                .then(ClientCommands.literal("presence").executes(context -> {
                    Minecraft minecraft = Minecraft.getInstance();
                    String status = HostPresencePublisher.currentStatus(minecraft);
                    context.getSource().sendFeedback(Component.literal("[FriendLink] posting " + status + " presence..."));

                    CompletableFuture
                        .supplyAsync(() -> new OfficialFriendsClient(minecraft.getUser().getAccessToken(), ProxySelector.getDefault())
                            .presence(status, JoinInfoUpdate.emptyInvites()))
                        .whenComplete((presence, throwable) -> minecraft.execute(() -> {
                            if (throwable != null) {
                                Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
                                String message = cause instanceof OfficialFriendsException friendsException
                                    ? friendsException.userMessage()
                                    : cause.getClass().getSimpleName() + ": " + cause.getMessage();
                                context.getSource().sendFeedback(Component.literal("[FriendLink] presence failed: " + message));
                                LOGGER.warn("Official presence request failed", cause);
                                return;
                            }

                            sendPresenceData(context.getSource()::sendFeedback, presence);
                        }));
                    return 1;
                }))
                .then(ClientCommands.literal("signaling").executes(context -> {
                    Minecraft minecraft = Minecraft.getInstance();
                    User user = minecraft.getUser();
                    context.getSource().sendFeedback(Component.literal("[FriendLink] connecting official signaling..."));

                    SignalingServiceClient signaling = new SignalingServiceClient(user);
                    signaling.connect()
                        .thenCompose(ignored -> signaling.requestTurnAuth())
                        .whenComplete((iceServer, throwable) -> {
                            signaling.close();
                            minecraft.execute(() -> {
                                if (throwable != null) {
                                    Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
                                    context.getSource().sendFeedback(Component.literal("[FriendLink] signaling failed: "
                                        + cause.getClass().getSimpleName() + ": " + cause.getMessage()));
                                    LOGGER.warn("Official signaling request failed", cause);
                                    return;
                                }

                                context.getSource().sendFeedback(Component.literal("[FriendLink] signaling OK, TURN urls="
                                    + iceServer.urls.size()));
                                iceServer.urls.stream()
                                    .limit(4)
                                    .forEach(url -> context.getSource().sendFeedback(Component.literal(" - " + url)));
                            });
                        });
                    return 1;
                }))
                .then(ClientCommands.literal("listen").executes(context -> {
                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft.getSingleplayerServer() == null) {
                        context.getSource().sendFeedback(Component.literal("[FriendLink] listen needs a single-player world first"));
                        return 0;
                    }
                    context.getSource().sendFeedback(Component.literal("[FriendLink] connecting signaling listener..."));
                    ExperimentalP2PSessionManager manager = experimentalManager(minecraft);
                    manager.connectSignaling()
                        .thenCompose(ignored -> manager.publishHostedPresence())
                        .orTimeout(35, TimeUnit.SECONDS)
                        .whenComplete((presence, throwable) -> minecraft.execute(() -> {
                            if (throwable != null) {
                                Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
                                context.getSource().sendFeedback(Component.literal("[FriendLink] listen failed: "
                                    + cause.getClass().getSimpleName() + ": " + cause.getMessage()));
                                LOGGER.warn("P2P listener failed", cause);
                                return;
                            }
                            context.getSource().sendFeedback(Component.literal("[FriendLink] listening for P2P offers; hosted presence posted; visible entries="
                                + presence.presence().size()));
                            context.getSource().sendFeedback(Component.literal("[FriendLink] HOST "
                                + minecraft.getUser().getName() + " UUID=" + minecraft.getUser().getProfileId()));
                        }));
                    return 1;
                }))
                .then(ClientCommands.literal("connect")
                    .then(ClientCommands.argument("peerPmid", StringArgumentType.word()).executes(context -> {
                        Minecraft minecraft = Minecraft.getInstance();
                        UUID peerPmid = Uuids.parseFlexible(StringArgumentType.getString(context, "peerPmid"));
                        context.getSource().sendFeedback(Component.literal("[FriendLink] resolving host id " + peerPmid));
                        ExperimentalP2PSessionManager manager = experimentalManager(minecraft);
                        PeerTargetResolver.resolve(minecraft, peerPmid)
                            .thenCompose(resolved -> {
                                minecraft.execute(() -> context.getSource().sendFeedback(Component.literal("[FriendLink] "
                                    + resolved.message())));
                                return manager.connectSignaling()
                                    .thenCompose(ignored -> manager.startOffer(resolved.targetPlayerId()));
                            })
                            .orTimeout(65, TimeUnit.SECONDS)
                            .whenComplete((ignored, throwable) -> minecraft.execute(() -> {
                                if (throwable != null) {
                                    Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
                                    context.getSource().sendFeedback(Component.literal("[FriendLink] connect failed: "
                                        + shortError(cause)));
                                    LOGGER.warn("P2P connect failed", cause);
                                    return;
                                }
                                context.getSource().sendFeedback(Component.literal("[FriendLink] WebRTC channel open; joining host world"));
                            }));
                        return 1;
                    })))
        ));
    }

    public static ExperimentalP2PSessionManager experimentalManager(Minecraft minecraft) {
        if (experimentalP2P == null) {
            experimentalP2P = new ExperimentalP2PSessionManager(minecraft);
        }
        return experimentalP2P;
    }

    private static void sendFriendData(java.util.function.Consumer<Component> sink, FriendData friendData) {
        sink.accept(Component.literal("[FriendLink] friends=" + friendData.friends().size()
            + " incoming=" + friendData.incomingRequests().size()
            + " outgoing=" + friendData.outgoingRequests().size()));
        friendData.friends().stream()
            .limit(10)
            .forEach(friend -> sink.accept(Component.literal(" - " + friend.name() + " " + friend.profileId())));
    }

    private static void sendPresenceData(java.util.function.Consumer<Component> sink, PresenceResponse presence) {
        sink.accept(Component.literal("[FriendLink] presence entries=" + presence.presence().size()));
        presence.presence().stream()
            .limit(10)
            .forEach(entry -> sink.accept(Component.literal(" - " + entry.profileId()
                + " status=" + entry.status()
                + " pmid=" + entry.pmid()
                + " invited=" + (entry.joinInfo() != null && entry.joinInfo().invited()))));
    }

    private static String shortError(Throwable cause) {
        String message = cause.getMessage();
        if (message != null && message.contains("Player not registered with the service")) {
            return "target player is not registered with signaling. Keep host on Listen, update both jars, and use the pmid from /friendlink presence.";
        }
        return cause.getClass().getSimpleName() + ": " + message;
    }
}
