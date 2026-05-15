package zz.friendlink.webrtc;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCDataChannelState;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class RtcHandshake {
    private final String id;
    private final RTCPeerConnection peerConnection;
    private final boolean initiator;
    private final Consumer<RTCIceCandidate> localCandidateHandler;
    private final CompletableFuture<HandshakeResult> result = new CompletableFuture<>();
    private final AtomicBoolean handedOff = new AtomicBoolean();
    private volatile RTCDataChannel dataChannel;

    public RtcHandshake(PeerConnectionFactory factory, RTCConfiguration configuration, String id, boolean initiator, Consumer<RTCIceCandidate> localCandidateHandler) {
        this.id = id;
        this.initiator = initiator;
        this.localCandidateHandler = localCandidateHandler;
        this.peerConnection = factory.createPeerConnection(configuration, new Observer());
        if (initiator) {
            RTCDataChannelInit init = new RTCDataChannelInit();
            init.ordered = true;
            wireDataChannel(peerConnection.createDataChannel("minecraft", init));
        }
    }

    public String id() {
        return id;
    }

    public boolean isInitiator() {
        return initiator;
    }

    public CompletableFuture<HandshakeResult> future() {
        return result;
    }

    public void abort(String reason) {
        result.completeExceptionally(new IllegalStateException(reason));
        RtcChannel.dispose(peerConnection, dataChannel);
    }

    public CompletableFuture<String> createOffer() {
        return createOfferSdp()
            .thenCompose(description -> setLocalDescription(description).thenApply(ignored -> description.sdp));
    }

    public CompletableFuture<String> acceptOffer(String sdp) {
        RTCSessionDescription offer = new RTCSessionDescription(RTCSdpType.OFFER, sdp);
        return setRemoteDescription(offer)
            .thenCompose(ignored -> createAnswerSdp())
            .thenCompose(answer -> setLocalDescription(answer).thenApply(ignored -> answer.sdp));
    }

    public CompletableFuture<Void> applyAnswer(String sdp) {
        return setRemoteDescription(new RTCSessionDescription(RTCSdpType.ANSWER, sdp));
    }

    public CompletableFuture<Void> addRemoteIceCandidate(RTCIceCandidate candidate) {
        try {
            peerConnection.addIceCandidate(candidate);
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private CompletableFuture<RTCSessionDescription> createOfferSdp() {
        CompletableFuture<RTCSessionDescription> future = new CompletableFuture<>();
        peerConnection.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription description) {
                future.complete(description);
            }

            @Override
            public void onFailure(String error) {
                future.completeExceptionally(new IllegalStateException(error));
            }
        });
        return future;
    }

    private CompletableFuture<RTCSessionDescription> createAnswerSdp() {
        CompletableFuture<RTCSessionDescription> future = new CompletableFuture<>();
        peerConnection.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription description) {
                future.complete(description);
            }

            @Override
            public void onFailure(String error) {
                future.completeExceptionally(new IllegalStateException(error));
            }
        });
        return future;
    }

    private CompletableFuture<Void> setLocalDescription(RTCSessionDescription description) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        peerConnection.setLocalDescription(description, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                future.complete(null);
            }

            @Override
            public void onFailure(String error) {
                future.completeExceptionally(new IllegalStateException(error));
            }
        });
        return future;
    }

    private CompletableFuture<Void> setRemoteDescription(RTCSessionDescription description) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        peerConnection.setRemoteDescription(description, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                future.complete(null);
            }

            @Override
            public void onFailure(String error) {
                future.completeExceptionally(new IllegalStateException(error));
            }
        });
        return future;
    }

    private void tryComplete() {
        RTCDataChannel channel = dataChannel;
        if (channel != null && channel.getState() == RTCDataChannelState.OPEN && handedOff.compareAndSet(false, true)) {
            channel.unregisterObserver();
            result.complete(new HandshakeResult(peerConnection, channel));
        }
    }

    private void wireDataChannel(RTCDataChannel channel) {
        dataChannel = channel;
        channel.registerObserver(new RTCDataChannelObserver() {
            @Override
            public void onBufferedAmountChange(long previousAmount) {
            }

            @Override
            public void onStateChange() {
                tryComplete();
            }

            @Override
            public void onMessage(RTCDataChannelBuffer buffer) {
            }
        });
        tryComplete();
    }

    public record HandshakeResult(RTCPeerConnection peerConnection, RTCDataChannel dataChannel) {
    }

    private final class Observer implements PeerConnectionObserver {
        @Override
        public void onIceCandidate(RTCIceCandidate candidate) {
            localCandidateHandler.accept(candidate);
        }

        @Override
        public void onDataChannel(RTCDataChannel channel) {
            wireDataChannel(channel);
        }

        @Override
        public void onConnectionChange(RTCPeerConnectionState state) {
            if (state == RTCPeerConnectionState.FAILED || state == RTCPeerConnectionState.CLOSED || state == RTCPeerConnectionState.DISCONNECTED) {
                result.completeExceptionally(new IllegalStateException("Peer connection " + state));
            }
        }
    }
}
