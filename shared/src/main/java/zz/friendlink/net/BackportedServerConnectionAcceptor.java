package zz.friendlink.net;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;

public final class BackportedServerConnectionAcceptor {
    private BackportedServerConnectionAcceptor() {
    }

    public static void acceptChannel(ServerConnectionListener listener, Channel channel) {
        Connection connection = BackportedConnectionFactory.fromChannel(channel, PacketFlow.SERVERBOUND, null);
        connection.setListenerForServerboundHandshake(new ServerHandshakePacketListenerImpl(listener.getServer(), connection));
        listener.getConnections().add(connection);
    }
}
