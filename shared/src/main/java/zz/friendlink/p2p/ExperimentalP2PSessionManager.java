package zz.friendlink.p2p;

import com.google.gson.JsonObject;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import zz.friendlink.FriendLinkClient;
import zz.friendlink.net.BackportedConnectionFactory;
import zz.friendlink.net.BackportedServerConnectionAcceptor;
import zz.friendlink.i18n.P2PTexts;
import zz.friendlink.signaling.SignalingMessages;
import zz.friendlink.signaling.SignalingServiceClient;
import zz.friendlink.friends.model.PresenceResponse;
import zz.friendlink.webrtc.RtcChannel;
import zz.friendlink.webrtc.RtcHandshake;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class ExperimentalP2PSessionManager implements AutoCloseable {
    private static final long HOST_KEEPALIVE_SECONDS = 90L;

    private final Minecraft minecraft;
    private final SignalingServiceClient signaling;
    private final PeerConnectionFactory peerConnectionFactory = new PeerConnectionFactory();
    private final Map<UUID, RtcHandshake> handshakes = new ConcurrentHashMap<>();
    private final Set<UUID> connectingNotices = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService presenceExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "FriendLink-PresenceKeepAlive");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledFuture<?> hostedPresenceTask;

    public ExperimentalP2PSessionManager(Minecraft minecraft) {
        this.minecraft = minecraft;
        this.signaling = new SignalingServiceClient(minecraft.getUser());
        this.signaling.setReceiveHandler(this::handleSignalingMessage);
    }

    public CompletableFuture<Void> connectSignaling() {
        return signaling.connect();
    }

    public CompletableFuture<PresenceResponse> publishHostedPresence() {
        stopHostedPresenceKeepAlive();
        return HostPresencePublisher.postHosted(minecraft)
            .whenComplete((presence, throwable) -> {
                if (throwable == null) {
                    startHostedPresenceKeepAlive();
                }
            });
    }

    public CompletableFuture<Void> startOffer(UUID peerPmid) {
        return signaling.requestTurnAuth().thenCompose(iceServer -> {
            String sessionId = UUID.randomUUID().toString();
            RtcHandshake handshake = createHandshake(peerPmid, sessionId, true, iceServer);
            return handshake.createOffer()
                .thenCompose(sdp -> signaling.sendClientMessage(peerPmid, SignalingMessages.offer(sessionId, sdp, minecraft.getUser().getName())))
                .thenCompose(ignored -> handshake.future())
                .thenApply(ignored -> null);
        });
    }

    private RtcHandshake createHandshake(UUID peerPmid, String sessionId, boolean initiator, RTCIceServer iceServer) {
        RTCConfiguration configuration = new RTCConfiguration();
        configuration.iceServers.add(iceServer);
        RtcHandshake handshake = new RtcHandshake(peerConnectionFactory, configuration, sessionId, initiator,
            candidate -> signaling.sendClientMessage(peerPmid, SignalingMessages.iceCandidate(sessionId, candidate)));
        handshakes.put(peerPmid, handshake);
        handshake.future().whenComplete((result, throwable) -> {
            handshakes.remove(peerPmid, handshake);
            if (throwable != null) {
                return;
            }
            if (initiator) {
                joinHost(result);
            } else {
                acceptGuest(result);
            }
        });
        return handshake;
    }

    private void handleSignalingMessage(UUID peerPmid, JsonObject message) {
        if (!message.has("type")) {
            String code = message.has("Code") ? message.get("Code").getAsString() : "?";
            String errorMessage = message.has("Message") ? message.get("Message").getAsString() : message.toString();
            String text = "Signaling error from " + peerPmid + ": " + code + " " + errorMessage;
            FriendLinkClient.LOGGER.warn(text);
            abortHandshakes(text);
            return;
        }
        String type = message.get("type").getAsString();
        if ("OFFER".equals(type)) {
            handleOffer(peerPmid, message);
        } else if ("ANSWER".equals(type)) {
            RtcHandshake handshake = handshakes.get(peerPmid);
            if (handshake != null) {
                handshake.applyAnswer(message.get("sdp").getAsString());
            }
        } else if ("ICE_CANDIDATE".equals(type)) {
            RtcHandshake handshake = handshakes.get(peerPmid);
            if (handshake != null) {
                handshake.addRemoteIceCandidate(SignalingMessages.toRtcIceCandidate(message));
            }
        }
    }

    private void handleOffer(UUID peerPmid, JsonObject message) {
        showConnectingNotice(peerPmid, message);
        String sessionId = message.get("sessionId").getAsString();
        String sdp = message.get("sdp").getAsString();
        signaling.requestTurnAuth()
            .thenCompose(iceServer -> {
                RtcHandshake handshake = createHandshake(peerPmid, sessionId, false, iceServer);
                return handshake.acceptOffer(sdp)
                    .thenCompose(answer -> signaling.sendClientMessage(peerPmid, SignalingMessages.answer(sessionId, answer)));
            })
            .whenComplete((ignored, throwable) -> {
                if (throwable != null) {
                    FriendLinkClient.LOGGER.warn("Failed to answer P2P offer from {}", peerPmid, throwable);
                }
            });
    }

    private void joinHost(RtcHandshake.HandshakeResult result) {
        minecraft.execute(() -> {
            if (minecraft.level != null || minecraft.getSingleplayerServer() != null) {
                minecraft.disconnectWithProgressScreen(false);
            }

            Connection connection = BackportedConnectionFactory.fromChannel(
                new RtcChannel(result),
                PacketFlow.CLIENTBOUND,
                minecraft.getDebugOverlay().getBandwidthLogger()
            );
            ServerData serverData = new ServerData("FriendLink", "rtc-peer", ServerData.Type.LAN);
            connection.initiateServerboundPlayConnection(
                "rtc-peer",
                0,
                LoginProtocols.SERVERBOUND,
                LoginProtocols.CLIENTBOUND,
                new ClientHandshakePacketListenerImpl(
                    connection,
                    minecraft,
                    serverData,
                    (Screen) null,
                    false,
                    null,
                    component -> {
                    },
                    new LevelLoadTracker(),
                    null
                ),
                false
            );
            connection.send(new ServerboundHelloPacket(minecraft.getUser().getName(), minecraft.getUser().getProfileId()));
            setPendingConnection(connection);
        });
    }

    private void acceptGuest(RtcHandshake.HandshakeResult result) {
        Minecraft serverOwner = minecraft;
        IntegratedServer server = serverOwner.getSingleplayerServer();
        if (server == null) {
            RtcChannel.dispose(result);
            return;
        }
        server.execute(() -> {
            IntegratedServer currentServer = serverOwner.getSingleplayerServer();
            if (currentServer == null) {
                RtcChannel.dispose(result);
                return;
            }
            BackportedServerConnectionAcceptor.acceptChannel(currentServer.getConnection(), new RtcChannel(result));
        });
    }

    @Override
    public void close() {
        stopHostedPresenceKeepAlive();
        presenceExecutor.shutdownNow();
        signaling.close();
        abortHandshakes("P2P manager closed");
        peerConnectionFactory.dispose();
    }

    private void startHostedPresenceKeepAlive() {
        hostedPresenceTask = presenceExecutor.scheduleWithFixedDelay(() -> {
            if (minecraft.getSingleplayerServer() == null) {
                stopHostedPresenceKeepAlive();
                return;
            }

            signaling.connect()
                .thenCompose(ignored -> HostPresencePublisher.postHosted(minecraft))
                .whenComplete((presence, throwable) -> {
                    if (throwable != null) {
                        FriendLinkClient.LOGGER.warn("Hosted signaling/presence keepalive failed", throwable);
                        return;
                    }
                    FriendLinkClient.LOGGER.info("Hosted signaling/presence keepalive OK, visible entries={}",
                        presence.presence().size());
                });
        }, HOST_KEEPALIVE_SECONDS, HOST_KEEPALIVE_SECONDS, TimeUnit.SECONDS);
    }

    private void stopHostedPresenceKeepAlive() {
        if (hostedPresenceTask != null) {
            hostedPresenceTask.cancel(false);
            hostedPresenceTask = null;
        }
    }

    private void abortHandshakes(String reason) {
        handshakes.values().forEach(handshake -> handshake.abort(reason));
        handshakes.clear();
    }

    private void setPendingConnection(Connection connection) {
        try {
            java.lang.reflect.Field field = Minecraft.class.getDeclaredField("pendingConnection");
            field.setAccessible(true);
            field.set(minecraft, connection);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to set Minecraft pendingConnection", exception);
        }
    }

    private void showConnectingNotice(UUID peerPmid, JsonObject message) {
        if (!connectingNotices.add(peerPmid)) {
            return;
        }
        String name = message.has("playerName") && !message.get("playerName").isJsonNull()
            ? message.get("playerName").getAsString()
            : peerPmid.toString().substring(0, 8);
        minecraft.execute(() -> minecraft.gui.getChat().addClientSystemMessage(P2PTexts.c("status.player_connecting", name)));
    }
}
