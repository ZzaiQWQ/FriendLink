package zz.friendlink.diagnostics;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class P2PDiagnostics {
    private static final String WEBRTC_FACTORY_CLASS = "dev.onvoid.webrtc.PeerConnectionFactory";
    private static final String WEBRTC_PEER_CLASS = "dev.onvoid.webrtc.RTCPeerConnection";

    private P2PDiagnostics() {
    }

    public static P2PStatus collect(Minecraft minecraft) {
        User user = minecraft.getUser();
        List<String> missingClasses = new ArrayList<>();
        boolean webRtcApiPresent = classPresent(WEBRTC_FACTORY_CLASS, missingClasses)
            && classPresent(WEBRTC_PEER_CLASS, missingClasses);

        Optional<String> xuid = user.getXuid();
        Optional<String> clientId = user.getClientId();
        boolean hasToken = user.getAccessToken() != null && !user.getAccessToken().isBlank();

        return new P2PStatus(
            user.getName(),
            user.getProfileId().toString(),
            hasToken,
            xuid.isPresent(),
            clientId.isPresent(),
            webRtcApiPresent,
            missingClasses
        );
    }

    private static boolean classPresent(String className, List<String> missingClasses) {
        try {
            Class.forName(className, false, P2PDiagnostics.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException exception) {
            missingClasses.add(className);
            return false;
        }
    }
}
