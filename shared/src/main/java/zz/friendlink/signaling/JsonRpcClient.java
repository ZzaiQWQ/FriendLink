package zz.friendlink.signaling;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.net.http.WebSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

final class JsonRpcClient implements WebSocket.Listener {
    private final ScheduledExecutorService executor;
    private final MethodHandler methodHandler;
    private final Consumer<Throwable> disconnectHandler;
    private final Map<Integer, CompletableFuture<JsonElement>> pendingRequests = new HashMap<>();
    private final StringBuilder messageBuffer = new StringBuilder();
    private CompletableFuture<Void> sendChain = CompletableFuture.completedFuture(null);
    private volatile WebSocket webSocket;
    private int transactionId;
    private volatile boolean closing;
    private volatile boolean tornDown;

    JsonRpcClient(ScheduledExecutorService executor, MethodHandler methodHandler, Consumer<Throwable> disconnectHandler) {
        this.executor = executor;
        this.methodHandler = methodHandler;
        this.disconnectHandler = disconnectHandler;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        executor.execute(() -> {
            this.webSocket = webSocket;
            webSocket.request(1);
        });
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        executor.execute(() -> {
            try {
                appendAndDispatch(data.toString(), last);
                webSocket.request(1);
            } catch (RuntimeException exception) {
                teardown(new IOException("WebSocket message handling failed", exception), !closing);
            }
        });
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        executor.execute(() -> teardown(new IOException("WebSocket closed " + statusCode + ": " + reason), !closing));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        executor.execute(() -> teardown(new IOException("WebSocket errored", error), !closing));
    }

    CompletableFuture<JsonElement> sendRequest(String method, List<JsonElement> params) {
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        executor.execute(() -> {
            WebSocket socket = webSocket;
            if (socket == null || tornDown || socket.isOutputClosed()) {
                future.completeExceptionally(new IOException("WebSocket is not connected"));
                return;
            }

            int id = ++transactionId;
            JsonObject request = new JsonObject();
            request.addProperty("jsonrpc", "2.0");
            request.addProperty("id", id);
            request.addProperty("method", method);
            JsonArray paramsArray = new JsonArray();
            params.forEach(paramsArray::add);
            request.add("params", paramsArray);
            pendingRequests.put(id, future);
            sendChain = sendChain.thenCompose(ignored -> socket.sendText(request.toString(), true).thenApply(sent -> (Void) null))
                .exceptionally(error -> {
                    executor.execute(() -> {
                        CompletableFuture<JsonElement> pending = pendingRequests.remove(id);
                        if (pending != null) {
                            pending.completeExceptionally(new IOException("WebSocket send failed", error));
                        }
                        teardown(new IOException("WebSocket send failed", error), !closing);
                    });
                    return (Void) null;
                });
        });
        return future;
    }

    CompletableFuture<Void> close() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        executor.execute(() -> {
            WebSocket socket = webSocket;
            closing = true;
            teardown(new IOException("JSON-RPC client closed"), false);
            if (socket == null || socket.isOutputClosed()) {
                future.complete(null);
            } else {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown").whenComplete((ignored, error) -> future.complete(null));
            }
        });
        return future;
    }

    private void appendAndDispatch(String data, boolean last) {
        if (messageBuffer.length() + data.length() > 65536) {
            messageBuffer.setLength(0);
            return;
        }
        messageBuffer.append(data);
        if (!last) {
            return;
        }
        String message = messageBuffer.toString();
        messageBuffer.setLength(0);
        dispatch(message);
    }

    private void dispatch(String message) {
        JsonObject object = JsonParser.parseString(message).getAsJsonObject();
        JsonElement id = object.get("id");
        JsonElement result = object.get("result");
        JsonElement error = object.get("error");
        JsonElement method = object.get("method");

        if (method != null && result == null && error == null) {
            methodHandler.onMethod(this, id, method.getAsString(), object.get("params"));
            return;
        }

        if (id != null && id.isJsonPrimitive()) {
            CompletableFuture<JsonElement> pending = pendingRequests.remove(id.getAsInt());
            if (pending == null) {
                return;
            }
            if (error != null && error.isJsonObject()) {
                pending.completeExceptionally(new IOException(error.toString()));
            } else {
                pending.complete(result == null ? new JsonObject() : result);
            }
        }
    }

    private void sendResponse(JsonElement id, JsonElement result) {
        WebSocket socket = webSocket;
        if (socket == null || id == null || tornDown || socket.isOutputClosed()) {
            return;
        }
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        response.add("result", result == null ? new JsonObject() : result);
        sendChain = sendChain.thenCompose(ignored -> socket.sendText(response.toString(), true).thenApply(sent -> (Void) null))
            .exceptionally(error -> {
                executor.execute(() -> teardown(new IOException("WebSocket response send failed", error), !closing));
                return (Void) null;
            });
    }

    void ack(JsonElement id) {
        sendResponse(id, new JsonObject());
    }

    void pong() {
        WebSocket socket = webSocket;
        if (socket == null || tornDown || socket.isOutputClosed()) {
            return;
        }
        JsonObject notification = new JsonObject();
        notification.addProperty("jsonrpc", "2.0");
        notification.addProperty("method", "System_Pong_v1_0");
        notification.add("params", new JsonArray());
        sendChain = sendChain.thenCompose(ignored -> socket.sendText(notification.toString(), true).thenApply(sent -> (Void) null))
            .exceptionally(error -> {
                executor.execute(() -> teardown(new IOException("WebSocket pong send failed", error), !closing));
                return (Void) null;
            });
    }

    boolean isUsable() {
        WebSocket socket = webSocket;
        return socket != null && !tornDown && !socket.isInputClosed() && !socket.isOutputClosed();
    }

    private void teardown(Throwable error, boolean notify) {
        if (tornDown) {
            return;
        }
        tornDown = true;
        webSocket = null;
        pendingRequests.values().forEach(future -> future.completeExceptionally(error));
        pendingRequests.clear();
        if (notify) {
            disconnectHandler.accept(error);
        }
    }

    interface MethodHandler {
        void onMethod(JsonRpcClient client, JsonElement id, String method, JsonElement params);
    }
}
