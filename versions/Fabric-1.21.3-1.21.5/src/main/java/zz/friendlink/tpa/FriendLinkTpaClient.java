package zz.friendlink.tpa;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class FriendLinkTpaClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        TpaNetworking.registerS2cPayload();
        ClientPlayNetworking.registerGlobalReceiver(
            TpaNetworking.INSTALLED_TYPE,
            (payload, context) -> {
            }
        );
    }
}
