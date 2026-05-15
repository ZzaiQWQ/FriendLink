package zz.friendlink.net;

/**
 * Marker for the first hard porting point.
 *
 * <p>Minecraft 26.2 adds Connection.fromChannel(Channel, PacketFlow, LocalSampleLogger).
 * The official WebRTC transport uses RtcChannel, which is a Netty Channel, then hands it
 * to Connection through that method. Minecraft 26.1.2 does not expose this factory, so
 * this mod will add an equivalent adapter before the real P2P connect flow is wired.
 *
 * <p>BackportedConnectionFactory currently detects whether the native method exists and
 * fails loudly on 26.1. The next real porting step is a mixin that sets Connection.channel,
 * address and packet handlers for a supplied Netty Channel.
 */
public final class ConnectionBackportNotes {
    private ConnectionBackportNotes() {
    }
}
