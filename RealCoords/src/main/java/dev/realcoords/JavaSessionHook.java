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

/**
 * Non-mutating probe for the Java-side MCProtocolLib pipeline.
 *
 * This deliberately does not transform packets. It verifies that the downstream
 * channel can be reached and that decoded inbound / outbound Packet instances
 * pass through a handler inserted immediately before MCProtocolLib's manager.
 */
final class JavaSessionHook {
    private static final String HANDLER_NAME = "realcoords-java-probe";
    private static final long DISCOVERY_TIMEOUT_SECONDS = 30;

    private JavaSessionHook() {
    }

    static void install(GeyserSession geyserSession, ExtensionLogger log) {
        AtomicBoolean finished = new AtomicBoolean();
        ScheduledFuture<?> poller = geyserSession.getTickEventLoop().scheduleAtFixedRate(() -> {
            if (finished.get()) {
                return;
            }

            DownstreamSession downstream = geyserSession.getDownstream();
            if (downstream == null) {
                return;
            }

            Session session = downstream.getSession();
            if (session.isConnected()) {
                finished.set(true);
                installOnChannel(session, log);
                return;
            }

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
                        installOnChannel(session, log);
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
                installOnChannel(session, log);
            }
        }, 0, 50, TimeUnit.MILLISECONDS);

        geyserSession.getTickEventLoop().schedule(() -> {
            if (finished.compareAndSet(false, true)) {
                poller.cancel(false);
                log.warning("[RealCoords] Java hook probe timed out before downstream channel became available");
            }
        }, DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static void installOnChannel(Session session, ExtensionLogger log) {
        Channel channel;
        try {
            channel = session.getChannel();
        } catch (RuntimeException exception) {
            log.error("[RealCoords] Java hook probe could not access downstream channel", exception);
            return;
        }

        channel.eventLoop().execute(() -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(HANDLER_NAME) != null) {
                return;
            }
            if (pipeline.get(NetworkConstants.MANAGER_NAME) == null) {
                log.error("[RealCoords] Java hook probe: MCProtocolLib manager handler not found; pipeline=" + pipeline.names());
                return;
            }

            JavaPacketProbe probe = new JavaPacketProbe(log);
            pipeline.addBefore(NetworkConstants.MANAGER_NAME, HANDLER_NAME, probe);
            log.info("[RealCoords] Java hook probe installed; pipeline=" + pipeline.names());
        });
    }

    private static final class JavaPacketProbe extends ChannelDuplexHandler {
        private final ExtensionLogger log;
        private final AtomicBoolean firstClientbound = new AtomicBoolean();
        private final AtomicBoolean firstServerbound = new AtomicBoolean();

        private JavaPacketProbe(ExtensionLogger log) {
            this.log = log;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
            if (message instanceof Packet packet && firstClientbound.compareAndSet(false, true)) {
                log.info("[RealCoords] Java hook first clientbound packet: " + packet.getClass().getName());
            }
            super.channelRead(ctx, message);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) throws Exception {
            if (message instanceof Packet packet && firstServerbound.compareAndSet(false, true)) {
                log.info("[RealCoords] Java hook first serverbound packet: " + packet.getClass().getName());
            }
            super.write(ctx, message, promise);
        }
    }
}
