package dev.realcoords;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.ScheduledFuture;
import org.geysermc.geyser.api.extension.ExtensionLogger;
import org.geysermc.geyser.session.DownstreamSession;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.network.NetworkConstants;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.ConnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.DisconnectingEvent;
import org.geysermc.mcprotocollib.network.event.session.PacketErrorEvent;
import org.geysermc.mcprotocollib.network.event.session.PacketSendingEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionListener;
import org.geysermc.mcprotocollib.network.packet.Packet;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coordinate transformer for the Java-side MCProtocolLib pipeline.
 *
 * The handler rewrites decoded clientbound packets and outgoing serverbound packets
 * immediately before MCProtocolLib's manager.
 */
final class JavaSessionHook {
    private static final String HANDLER_NAME = "realcoords-java-transform";
    private static final long DISCOVERY_TIMEOUT_SECONDS = 30;

    private JavaSessionHook() {
    }

    static void install(GeyserSession geyserSession, ShiftConfig config, JavaOriginState origin, ExtensionLogger log) {
        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean listenerRegistered = new AtomicBoolean();
        AtomicReference<ScheduledFuture<?>> pollerReference = new AtomicReference<>();

        ScheduledFuture<?> poller = geyserSession.getTickEventLoop().scheduleAtFixedRate(() -> {
            if (finished.get()) {
                ScheduledFuture<?> scheduled = pollerReference.get();
                if (scheduled != null) scheduled.cancel(false);
                return;
            }

            DownstreamSession downstream = geyserSession.getDownstream();
            if (downstream == null) {
                return;
            }

            Session session = downstream.getSession();
            if (session.isConnected()) {
                if (finished.compareAndSet(false, true)) {
                    installOnChannel(session, config, origin, log);
                }
                ScheduledFuture<?> scheduled = pollerReference.get();
                if (scheduled != null) scheduled.cancel(false);
                return;
            }

            if (listenerRegistered.compareAndSet(false, true)) {
                // Register before checking isConnected again to close the race with channelActive.
                session.addListener(new SessionListener() {
                    @Override
                    public void packetReceived(Session ignored, Packet packet) {
                    }

                    @Override
                    public void packetSending(PacketSendingEvent event) {
                    }

                    @Override
                    public void packetSent(Session ignored, Packet packet) {
                    }

                    @Override
                    public void packetError(PacketErrorEvent event) {
                    }

                    @Override
                    public void connected(ConnectedEvent event) {
                        if (finished.compareAndSet(false, true)) {
                            installOnChannel(session, config, origin, log);
                        }
                        session.removeListener(this);
                    }

                    @Override
                    public void disconnecting(DisconnectingEvent event) {
                        session.removeListener(this);
                    }

                    @Override
                    public void disconnected(DisconnectedEvent event) {
                        session.removeListener(this);
                    }
                });
                if (session.isConnected() && finished.compareAndSet(false, true)) {
                    installOnChannel(session, config, origin, log);
                }
            }
        }, 0, 50, TimeUnit.MILLISECONDS);
        pollerReference.set(poller);

        geyserSession.getTickEventLoop().schedule(() -> {
            if (finished.compareAndSet(false, true)) {
                poller.cancel(false);
                log.warning("[RealCoords] Java hook timed out before downstream channel became available");
            }
        }, DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static void installOnChannel(Session session, ShiftConfig config, JavaOriginState origin, ExtensionLogger log) {
        Channel channel;
        try {
            channel = session.getChannel();
        } catch (RuntimeException exception) {
            log.error("[RealCoords] Java hook probe could not access downstream channel", exception);
            return;
        }

        Runnable install = () -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(HANDLER_NAME) != null) {
                return;
            }
            if (!channel.isActive()) {
                log.error("[RealCoords] Java hook probe: downstream channel is not active; refusing late installation");
                return;
            }
            if (pipeline.get(NetworkConstants.MANAGER_NAME) == null) {
                log.error("[RealCoords] Java hook probe: MCProtocolLib manager handler not found; pipeline=" + pipeline.names());
                return;
            }

            JavaPacketTransformHandler handler = new JavaPacketTransformHandler(new JavaPacketTransform(origin, config.maxJump), log);
            pipeline.addBefore(NetworkConstants.MANAGER_NAME, HANDLER_NAME, handler);
            log.info("[RealCoords] Java coordinate transform installed; pipeline=" + pipeline.names());
        };

        // ConnectedEvent is fired from channelActive. Install inline when already on
        // the channel event loop so the handler is present before MCProtocolLib flushes
        // its pending packet queue after firing ConnectedEvent.
        if (channel.eventLoop().inEventLoop()) {
            install.run();
        } else {
            channel.eventLoop().execute(install);
        }
    }

    private static final class JavaPacketTransformHandler extends ChannelDuplexHandler {
        private final ExtensionLogger log;
        private final AtomicBoolean firstClientbound = new AtomicBoolean();
        private final AtomicBoolean firstServerbound = new AtomicBoolean();

        private final JavaPacketTransform transform;

        private JavaPacketTransformHandler(JavaPacketTransform transform, ExtensionLogger log) {
            this.transform = transform;
            this.log = log;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
            if (message instanceof Packet packet && firstClientbound.compareAndSet(false, true)) {
                log.info("[RealCoords] Java hook first clientbound packet: " + packet.getClass().getName());
            }
            if (message instanceof Packet packet) {
                super.channelRead(ctx, transform.clientbound(packet));
                return;
            }
            super.channelRead(ctx, message);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) throws Exception {
            if (message instanceof Packet packet && firstServerbound.compareAndSet(false, true)) {
                log.info("[RealCoords] Java hook first serverbound packet: " + packet.getClass().getName());
            }
            if (message instanceof Packet packet) {
                Packet transformed = transform.serverbound(packet);
                if (transformed != null) {
                    super.write(ctx, transformed, promise);
                } else {
                    promise.setSuccess();
                    log.warning("[RealCoords] dropped an implausible Java serverbound player movement packet");
                }
                return;
            }
            super.write(ctx, message, promise);
        }
    }
}
