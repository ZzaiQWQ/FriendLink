package zz.friendlink.net;

import io.netty.channel.Channel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import net.minecraft.network.Connection;
import net.minecraft.network.BandwidthDebugMonitor;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.util.debugchart.LocalSampleLogger;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

public final class BackportedConnectionFactory {
    private static final Method FROM_CHANNEL = findFromChannel();
    private static final Field CHANNEL_FIELD = findField("channel");
    private static final Field ADDRESS_FIELD = findField("address");
    private static final Field BANDWIDTH_FIELD = findField("bandwidthDebugMonitor");
    private static final SocketAddress FALLBACK_REMOTE_ADDRESS = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

    private BackportedConnectionFactory() {
    }

    public static Connection fromChannel(Channel channel, PacketFlow packetFlow, LocalSampleLogger bandwidthLogger) {
        if (FROM_CHANNEL != null) {
            try {
                return (Connection) FROM_CHANNEL.invoke(null, channel, packetFlow, bandwidthLogger);
            } catch (IllegalAccessException | InvocationTargetException exception) {
                throw new IllegalStateException("Failed to call native Connection.fromChannel", exception);
            }
        }

        Connection connection = new Connection(packetFlow);
        if (bandwidthLogger != null) {
            connection.setBandwidthLogger(bandwidthLogger);
        }

        BandwidthDebugMonitor bandwidthMonitor = getBandwidthMonitor(connection);
        channel.pipeline().addLast("timeout", new ReadTimeoutHandler(30));
        Connection.configureSerialization(channel.pipeline(), packetFlow, false, bandwidthMonitor);
        connection.configurePacketHandler(channel.pipeline());

        setField(CHANNEL_FIELD, connection, channel);
        SocketAddress remoteAddress = channel.remoteAddress();
        setField(ADDRESS_FIELD, connection, remoteAddress == null ? FALLBACK_REMOTE_ADDRESS : remoteAddress);

        EventLoopGroupHolder.local().eventLoopGroup().register(channel).syncUninterruptibly();
        return connection;
    }

    public static boolean hasNativeFromChannel() {
        return FROM_CHANNEL != null;
    }

    private static Method findFromChannel() {
        try {
            return Connection.class.getDeclaredMethod("fromChannel", Channel.class, PacketFlow.class, LocalSampleLogger.class);
        } catch (NoSuchMethodException exception) {
            return null;
        }
    }

    private static Field findField(String name) {
        try {
            Field field = Connection.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static BandwidthDebugMonitor getBandwidthMonitor(Connection connection) {
        try {
            return (BandwidthDebugMonitor) BANDWIDTH_FIELD.get(connection);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Failed to read Connection bandwidth monitor", exception);
        }
    }

    private static void setField(Field field, Connection connection, Object value) {
        try {
            field.set(connection, value);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Failed to set Connection field " + field.getName(), exception);
        }
    }
}
