package zz.friendlink.tpa;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class TpaNetworking {
    public static final CustomPacketPayload.Type<TpaInstalledPayload> INSTALLED_TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("friendlink", "tpa_installed"));

    private static boolean s2cRegistered;

    private TpaNetworking() {
    }

    public static synchronized void registerS2cPayload() {
        if (s2cRegistered) {
            return;
        }
        PayloadTypeRegistry.playS2C().register(INSTALLED_TYPE, TpaInstalledPayload.CODEC);
        s2cRegistered = true;
    }

    public record TpaInstalledPayload() implements CustomPacketPayload {
        public static final TpaInstalledPayload INSTANCE = new TpaInstalledPayload();
        public static final StreamCodec<RegistryFriendlyByteBuf, TpaInstalledPayload> CODEC =
            StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return INSTALLED_TYPE;
        }
    }
}
