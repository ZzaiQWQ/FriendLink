package zz.friendlink.webrtc;

import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCDataChannelState;
import dev.onvoid.webrtc.RTCPeerConnection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.EventLoop;
import io.netty.channel.SingleThreadEventLoop;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

public final class RtcChannel extends AbstractChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(false);
    private static final int MAX_CHUNK_SIZE = 262144;
    private static final long HIGH_WATER_MARK = 1048576L;
    private static final int BACKPRESSURE_FLAG = 1;
    private static final SocketAddress LOCAL_ADDRESS = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
    private static final SocketAddress REMOTE_ADDRESS = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

    private final RtcHandshake.HandshakeResult handshakeResult;
    private final ChannelConfig config;
    private volatile boolean closed;
    private volatile boolean activated;
    private boolean writeStalled;

    public RtcChannel(RtcHandshake.HandshakeResult handshakeResult) {
        super(null);
        this.handshakeResult = handshakeResult;
        this.config = new DefaultChannelConfig(this);
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new AbstractUnsafe() {
            @Override
            public void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
                promise.setFailure(new UnsupportedOperationException("RtcChannel is already connected by WebRTC"));
            }
        };
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return loop instanceof SingleThreadEventLoop;
    }

    @Override
    public boolean isOpen() {
        return !closed;
    }

    @Override
    public boolean isActive() {
        return activated && !closed;
    }

    @Override
    protected SocketAddress localAddress0() {
        return LOCAL_ADDRESS;
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return REMOTE_ADDRESS;
    }

    @Override
    protected void doRegister() {
        eventLoop().execute(() -> {
            handleStateChange(handshakeResult.dataChannel().getState());
            handshakeResult.dataChannel().registerObserver(new Observer());
        });
    }

    @Override
    protected void doBind(SocketAddress localAddress) {
        throw new UnsupportedOperationException("RtcChannel cannot be bound");
    }

    @Override
    protected void doDisconnect() {
        closeFromTransport();
    }

    @Override
    protected void doClose() {
        if (closed) {
            return;
        }
        closed = true;
        dispose(handshakeResult);
    }

    @Override
    protected void doBeginRead() {
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        Object message;
        while ((message = in.current()) != null) {
            if (message instanceof ByteBuf byteBuf) {
                writeByteBuf(byteBuf);
            }
            in.remove();
            if (handshakeResult.dataChannel().getBufferedAmount() >= HIGH_WATER_MARK) {
                setWriteStalled(true);
                return;
            }
        }
    }

    public static void dispose(RtcHandshake.HandshakeResult result) {
        dispose(result.peerConnection(), result.dataChannel());
    }

    public static void dispose(RTCPeerConnection peerConnection, RTCDataChannel dataChannel) {
        if (dataChannel != null) {
            try {
                dataChannel.unregisterObserver();
            } catch (RuntimeException ignored) {
            }
            try {
                dataChannel.close();
            } catch (RuntimeException ignored) {
            }
            try {
                dataChannel.dispose();
            } catch (RuntimeException ignored) {
            }
        }
        if (peerConnection != null) {
            try {
                peerConnection.close();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void writeByteBuf(ByteBuf byteBuf) throws Exception {
        int readableBytes = byteBuf.readableBytes();
        int readerIndex = byteBuf.readerIndex();
        while (readableBytes > 0) {
            int size = Math.min(readableBytes, MAX_CHUNK_SIZE);
            byte[] bytes = new byte[size];
            byteBuf.getBytes(readerIndex, bytes);
            handshakeResult.dataChannel().send(new RTCDataChannelBuffer(ByteBuffer.wrap(bytes), true));
            readerIndex += size;
            readableBytes -= size;
        }
    }

    private void handleMessage(ByteBuf byteBuf) {
        if (closed || !activated || !config.isAutoRead()) {
            byteBuf.release();
            return;
        }
        pipeline().fireChannelRead(byteBuf);
        pipeline().fireChannelReadComplete();
    }

    private void handleStateChange(RTCDataChannelState state) {
        if (closed) {
            return;
        }
        if (state == RTCDataChannelState.OPEN) {
            if (!activated) {
                activated = true;
                pipeline().fireChannelActive();
            }
        } else if (state == RTCDataChannelState.CLOSING || state == RTCDataChannelState.CLOSED) {
            closeFromTransport();
        }
    }

    private void setWriteStalled(boolean stalled) {
        if (closed || writeStalled == stalled) {
            return;
        }
        writeStalled = stalled;
        ChannelOutboundBuffer buffer = unsafe().outboundBuffer();
        if (buffer != null) {
            buffer.setUserDefinedWritability(BACKPRESSURE_FLAG, !stalled);
        }
    }

    private void closeFromTransport() {
        if (!closed) {
            unsafe().close(voidPromise());
        }
    }

    private final class Observer implements RTCDataChannelObserver {
        @Override
        public void onBufferedAmountChange(long previousAmount) {
            if (handshakeResult.dataChannel().getBufferedAmount() < HIGH_WATER_MARK) {
                eventLoop().execute(() -> {
                    setWriteStalled(false);
                    flush();
                });
            }
        }

        @Override
        public void onStateChange() {
            eventLoop().execute(() -> {
                if (closed) {
                    return;
                }
                try {
                    handleStateChange(handshakeResult.dataChannel().getState());
                } catch (RuntimeException exception) {
                    closeFromTransport();
                }
            });
        }

        @Override
        public void onMessage(RTCDataChannelBuffer buffer) {
            ByteBuffer data = buffer.data;
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            ByteBuf byteBuf = Unpooled.wrappedBuffer(bytes);
            eventLoop().execute(() -> handleMessage(byteBuf));
        }
    }
}
