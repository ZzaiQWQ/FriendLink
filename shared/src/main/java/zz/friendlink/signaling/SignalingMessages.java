package zz.friendlink.signaling;

import com.google.gson.JsonObject;
import dev.onvoid.webrtc.RTCIceCandidate;

public final class SignalingMessages {
    private SignalingMessages() {
    }

    public static JsonObject joinRequest(String sessionId) {
        return message("JOIN_REQUEST", sessionId);
    }

    public static JsonObject joinAccepted(String sessionId) {
        return message("JOIN_ACCEPTED", sessionId);
    }

    public static JsonObject joinRejected(String sessionId) {
        return message("JOIN_REJECTED", sessionId);
    }

    public static JsonObject offer(String sessionId, String sdp, String playerName) {
        JsonObject message = message("OFFER", sessionId);
        message.addProperty("sdp", sdp);
        message.addProperty("playerName", playerName);
        return message;
    }

    public static JsonObject answer(String sessionId, String sdp) {
        JsonObject message = message("ANSWER", sessionId);
        message.addProperty("sdp", sdp);
        return message;
    }

    public static JsonObject iceCandidate(String sessionId, RTCIceCandidate candidate) {
        JsonObject message = message("ICE_CANDIDATE", sessionId);
        JsonObject iceCandidate = new JsonObject();
        iceCandidate.addProperty("candidate", candidate.sdp);
        if (candidate.sdpMid != null) {
            iceCandidate.addProperty("sdpMid", candidate.sdpMid);
        }
        iceCandidate.addProperty("sdpMLineIndex", candidate.sdpMLineIndex);
        message.add("iceCandidate", iceCandidate);
        return message;
    }

    public static RTCIceCandidate toRtcIceCandidate(JsonObject message) {
        JsonObject candidate = message.getAsJsonObject("iceCandidate");
        String sdpMid = candidate.has("sdpMid") && !candidate.get("sdpMid").isJsonNull()
            ? candidate.get("sdpMid").getAsString()
            : "0";
        int sdpMLineIndex = candidate.get("sdpMLineIndex").getAsInt();
        String sdp = candidate.get("candidate").getAsString();
        return new RTCIceCandidate(sdpMid, sdpMLineIndex, sdp);
    }

    private static JsonObject message(String type, String sessionId) {
        JsonObject message = new JsonObject();
        message.addProperty("type", type);
        message.addProperty("sessionId", sessionId);
        return message;
    }
}
