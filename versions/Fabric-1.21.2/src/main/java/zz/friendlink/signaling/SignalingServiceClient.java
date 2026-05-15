package zz.friendlink.signaling;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.onvoid.webrtc.RTCIceServer;
import net.minecraft.client.User;
import zz.friendlink.FriendLinkClient;
import zz.friendlink.util.Uuids;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public final class SignalingServiceClient implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private static final String BASE_URL = "https://signaling-afd.franchise.minecraft-services.net";
    private static final String CONFIGURATION_ENDPOINT = "/api/v1.0/configuration/java";
    private static final String WS_CONNECTION_ENDPOINT = "/ws/v1.0/messaging/connect/java";
    private static final Duration PING_INTERVAL = Duration.ofSeconds(50);

    private final User user;
    private final String sessionId = UUID.randomUUID().toString();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "FriendLink-Signaling");
        thread.setDaemon(true);
        return thread;
    });

    private HttpClient httpClient;
    private CompletableFuture<JsonRpcClient> websocketConnect;
    private ScheduledFuture<?> pingTask;
    private BiConsumer<UUID, JsonObject> receiveHandler = (peer, message) -> {
    };

    public SignalingServiceClient(User user) {
        this.user = user;
    }

    public void setReceiveHandler(BiConsumer<UUID, JsonObject> receiveHandler) {
        this.receiveHandler = receiveHandler == null ? (peer, message) -> {
        } : receiveHandler;
    }

    public CompletableFuture<Void> connect() {
        CompletableFuture<JsonRpcClient> existingConnect = websocketConnect;
        if (existingConnect != null) {
            return existingConnect.thenCompose(rpc -> {
                if (rpc.isUsable()) {
                    return CompletableFuture.<Void>completedFuture(null);
                }
                websocketConnect = null;
                return openConnection().<Void>thenApply(ignored -> null);
            }).exceptionallyCompose(error -> {
                websocketConnect = null;
                return openConnection().<Void>thenApply(ignored -> null);
            });
        }
        return openConnection().<Void>thenApply(ignored -> null);
    }

    private CompletableFuture<JsonRpcClient> openConnection() {
        FriendLinkClient.LOGGER.info("FriendLink signaling connect start");
        return CompletableFuture.supplyAsync(() -> {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .proxy(ProxySelector.getDefault())
                .build();
            httpClient = client;
            JsonRpcClient rpc = new JsonRpcClient(executor, this::onRpcMethod, this::onDisconnect);
            String requestId = UUID.randomUUID().toString();
            websocketConnect = getSignalingUri(client, requestId)
                .thenCompose(uri -> openWebSocket(client, rpc, uri, requestId));
            return websocketConnect;
        }, executor).thenCompose(future -> future).thenApply(rpc -> {
            FriendLinkClient.LOGGER.info("FriendLink signaling connected");
            return rpc;
        });
    }

    public CompletableFuture<Void> reconnect() {
        return disconnect().thenCompose(ignored -> connect());
    }

    public CompletableFuture<RTCIceServer> requestTurnAuth() {
        return sendRequest("Signaling_TurnAuth_v1_0", List.of()).thenApply(this::parseTurnAuth);
    }

    public CompletableFuture<Void> sendClientMessage(UUID peerProfileId, JsonObject message) {
        return sendRequest("Signaling_SendClientMessage_v1_0", List.of(
            new JsonPrimitive(UUID.randomUUID().toString()),
            new JsonPrimitive(peerProfileId.toString()),
            new JsonPrimitive(message.toString())
        )).thenApply(ignored -> null);
    }

    private CompletableFuture<String> getSignalingUri(HttpClient client, String requestId) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + CONFIGURATION_ENDPOINT))
            .header("x-mojangauth", user.getAccessToken())
            .header("Session-Id", sessionId)
            .header("Request-Id", requestId)
            .GET()
            .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                FriendLinkClient.LOGGER.info("FriendLink signaling configuration HTTP {}", response.statusCode());
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException("Signaling configuration failed: HTTP " + response.statusCode());
                }
                JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                String signalingUri = root.getAsJsonObject("result").get("signalingUri").getAsString();
                FriendLinkClient.LOGGER.info("FriendLink signaling uri {}", signalingUri);
                return signalingUri + WS_CONNECTION_ENDPOINT;
            });
    }

    private CompletableFuture<JsonRpcClient> openWebSocket(HttpClient client, JsonRpcClient rpc, String uri, String requestId) {
        return client.newWebSocketBuilder()
            .header("x-mojangauth", user.getAccessToken())
            .header("Session-Id", sessionId)
            .header("Request-Id", requestId)
            .buildAsync(URI.create(uri), rpc)
            .thenApply(webSocket -> {
                FriendLinkClient.LOGGER.info("FriendLink signaling websocket opened");
                schedulePing(rpc);
                return rpc;
            });
    }

    private CompletableFuture<JsonElement> sendRequest(String method, List<JsonElement> params) {
        CompletableFuture<JsonRpcClient> connect = websocketConnect;
        if (connect == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Signaling is not connected"));
        }
        return connect.thenCompose(rpc -> rpc.sendRequest(method, params));
    }

    private RTCIceServer parseTurnAuth(JsonElement result) {
        JsonObject root = result.getAsJsonObject();
        JsonArray servers = getArray(root, "TurnAuthServers", "turnAuthServers");
        if (servers == null || servers.isEmpty()) {
            throw new IllegalStateException("TURN auth response has no TurnAuthServers: " + result);
        }

        RTCIceServer iceServer = new RTCIceServer();
        JsonObject firstServer = servers.get(0).getAsJsonObject();
        iceServer.username = getString(firstServer, "Username", "username");
        iceServer.password = getString(firstServer, "Password", "password");

        for (JsonElement serverElement : servers) {
            JsonObject server = serverElement.getAsJsonObject();
            JsonArray urls = getArray(server, "Urls", "urls");
            if (urls == null) {
                continue;
            }
            for (JsonElement url : urls) {
                iceServer.urls.add(url.getAsString());
            }
        }

        if (iceServer.urls.isEmpty()) {
            throw new IllegalStateException("TURN auth response has no Urls: " + result);
        }
        return iceServer;
    }

    private static JsonArray getArray(JsonObject object, String primary, String fallback) {
        JsonElement value = object.get(primary);
        if (value == null || value.isJsonNull()) {
            value = object.get(fallback);
        }
        return value == null || value.isJsonNull() ? null : value.getAsJsonArray();
    }

    private static String getString(JsonObject object, String primary, String fallback) {
        JsonElement value = object.get(primary);
        if (value == null || value.isJsonNull()) {
            value = object.get(fallback);
        }
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private void onRpcMethod(JsonRpcClient client, JsonElement id, String method, JsonElement params) {
        if ("System_Pong_v1_0".equals(method)) {
            return;
        }
        if ("Signaling_ReceiveMessage_v1_0".equals(method)) {
            client.ack(id);
            try {
                handleReceiveMessage(params);
            } catch (RuntimeException exception) {
                FriendLinkClient.LOGGER.warn("Failed to handle signaling message params={}", params, exception);
            }
        }
    }

    private void handleReceiveMessage(JsonElement params) {
        if (params == null || !params.isJsonArray() || params.getAsJsonArray().isEmpty()) {
            return;
        }
        JsonArray array = params.getAsJsonArray();
        UUID from;
        JsonObject payload;

        if (array.size() == 1 && array.get(0).isJsonObject()) {
            JsonObject envelope = array.get(0).getAsJsonObject();
            from = getUuid(envelope, "from", "From", "fromPlayerId", "FromPlayerId", "playerId", "PlayerId");
            payload = getPayload(getField(envelope, "message", "Message", "payload", "Payload"));
        } else if (array.size() >= 3) {
            from = Uuids.parseFlexible(array.get(1).getAsString());
            payload = getPayload(array.get(2));
        } else if (array.size() >= 2) {
            from = Uuids.parseFlexible(array.get(0).getAsString());
            payload = getPayload(array.get(1));
        } else {
            return;
        }

        FriendLinkClient.LOGGER.info("Received signaling message from {} type={}", from,
            payload.has("type") ? payload.get("type").getAsString() : "<missing>");
        receiveHandler.accept(from, payload);
    }

    private static UUID getUuid(JsonObject object, String... names) {
        for (String name : names) {
            JsonElement value = object.get(name);
            if (value != null && !value.isJsonNull()) {
                return Uuids.parseFlexible(value.getAsString());
            }
        }
        throw new IllegalArgumentException("No UUID field in signaling message: " + object);
    }

    private static JsonElement getField(JsonObject object, String... names) {
        for (String name : names) {
            JsonElement value = object.get(name);
            if (value != null && !value.isJsonNull()) {
                return value;
            }
        }
        return null;
    }

    private static JsonObject getPayload(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            throw new IllegalArgumentException("Signaling message has no payload");
        }
        if (value.isJsonObject()) {
            return value.getAsJsonObject();
        }
        return JsonParser.parseString(value.getAsString()).getAsJsonObject();
    }

    private void schedulePing(JsonRpcClient rpc) {
        pingTask = executor.scheduleAtFixedRate(rpc::pong, PING_INTERVAL.toMillis(), PING_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void onDisconnect(Throwable error) {
        if (pingTask != null) {
            pingTask.cancel(false);
            pingTask = null;
        }
        websocketConnect = null;
    }

    public CompletableFuture<Void> disconnect() {
        CompletableFuture<JsonRpcClient> connect = websocketConnect;
        websocketConnect = null;
        if (pingTask != null) {
            pingTask.cancel(false);
            pingTask = null;
        }
        if (connect != null) {
            return connect.thenCompose(JsonRpcClient::close).exceptionally(error -> null);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {
        disconnect().whenComplete((ignored, error) -> executor.shutdownNow());
    }
}
