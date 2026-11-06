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
import org.geysermc.geyser.entity.type.player.SessionPlayerEntity;
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
            if (listenerRegistered.compareAndSet(false, true)) {
                // Attach the lifecycle listener before checking isConnected. This
                // catches channelActive even if it races with this discovery poll.
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
                            installOnChannel(session, geyserSession, config, origin, log);
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
                // If connection completed before listener registration, install now.
                if (session.isConnected() && finished.compareAndSet(false, true)) {
                    installOnChannel(session, geyserSession, config, origin, log);
                }
            }
        }, 0, 1, TimeUnit.MILLISECONDS);
        pollerReference.set(poller);

        geyserSession.getTickEventLoop().schedule(() -> {
            if (finished.compareAndSet(false, true)) {
                poller.cancel(false);
                log.warning("[RealCoords] Java hook timed out before downstream channel became available");
            }
        }, DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static void installOnChannel(Session session, GeyserSession geyserSession, ShiftConfig config, JavaOriginState origin, ExtensionLogger log) {
        Channel channel;
        try {
            channel = session.getChannel();
        } catch (RuntimeException exception) {
            log.error("[RealCoords] Java hook could not access downstream channel", exception);
            return;
        }

        Runnable install = () -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(HANDLER_NAME) != null) {
                return;
            }
            if (!channel.isActive()) {
                log.error("[RealCoords] Java hook: downstream channel is not active; refusing late installation");
                return;
            }
            if (pipeline.get(NetworkConstants.MANAGER_NAME) == null) {
                log.error("[RealCoords] Java hook: MCProtocolLib manager handler not found; pipeline=" + pipeline.names());
                return;
            }

            JavaPacketTransformHandler handler = new JavaPacketTransformHandler(new JavaPacketTransform(origin, config.maxJump, config), geyserSession, log);
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

    static final class JavaPacketTransformHandler extends ChannelDuplexHandler {
        private final ExtensionLogger log;
        private final AtomicBoolean firstClientbound = new AtomicBoolean();
        private final AtomicBoolean firstServerbound = new AtomicBoolean();
        private long lastGuardWarningMs;

        private final JavaPacketTransform transform;
        private final GeyserSession geyserSession;
        private final SyntheticResetGate syntheticResetGate = new SyntheticResetGate();
        private ChannelHandlerContext channelContext;
        private ScheduledFuture<?> syntheticResetTimeout;

        JavaPacketTransformHandler(JavaPacketTransform transform, ExtensionLogger log) {
            this(transform, null, log);
        }

        JavaPacketTransformHandler(JavaPacketTransform transform, GeyserSession geyserSession, ExtensionLogger log) {
            this.transform = transform;
            this.geyserSession = geyserSession;
            this.log = log;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            channelContext = ctx;
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            abortSyntheticReset();
            channelContext = null;
        }

        boolean beginSyntheticReset(int reservedTeleportId) {
            ChannelHandlerContext ctx = channelContext;
            if (ctx == null || !ctx.executor().inEventLoop()) {
                throw new IllegalStateException("Synthetic reset must begin on the downstream channel event loop");
            }
            if (!syntheticResetGate.begin(reservedTeleportId, 2)) return false;
            cancelSyntheticResetTimeout();
            syntheticResetTimeout = ctx.executor().schedule(() -> {
                if (log != null) log.warning("[RealCoords] synthetic reset acknowledgement timed out; closing downstream session to prevent synthetic packet leakage");
                abortSyntheticReset();
            }, transform.syntheticAckTimeoutMillis(), TimeUnit.MILLISECONDS);
            return true;
        }

        void abortSyntheticReset() {
            boolean wasActive = syntheticResetGate.active();
            syntheticResetGate.abort();
            cancelSyntheticResetTimeout();
            // Once synthetic packets have entered Geyser, a timeout/abort must not
            // release a late acknowledgement to the real Java server.
            if (wasActive && channelContext != null) channelContext.close();
        }

        private void cancelSyntheticResetTimeout() {
            ScheduledFuture<?> timeout = syntheticResetTimeout;
            syntheticResetTimeout = null;
            if (timeout != null) timeout.cancel(false);
        }

        int injectSyntheticReset(ChannelHandlerContext ctx, int reservedTeleportId) {
            if (!ctx.executor().inEventLoop()) {
                throw new IllegalStateException("Synthetic reset must run on the downstream channel event loop");
            }
            var respawns = transform.syntheticResetRespawns();
            var position = transform.syntheticPlayerPosition(reservedTeleportId);
            if (respawns.size() != 2 || position == null || !beginSyntheticReset(reservedTeleportId)) return 0;
            try {
                for (Packet respawn : respawns) ctx.fireChannelRead(respawn);
                ctx.fireChannelRead(position);
                return respawns.size() + 1;
            } catch (RuntimeException exception) {
                abortSyntheticReset();
                throw exception;
            }
        }

        int replayCachedChunks(ChannelHandlerContext ctx, int centerChunkX, int centerChunkZ, int limit) {
            if (!ctx.executor().inEventLoop()) {
                throw new IllegalStateException("Cached chunk replay must run on the downstream channel event loop");
            }
            var packets = transform.replayCachedChunks(centerChunkX, centerChunkZ, limit);
            for (Packet packet : packets) {
                ctx.fireChannelRead(packet);
            }
            return packets.size();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
            if (message instanceof Packet packet && firstClientbound.compareAndSet(false, true)) {
                if (log != null) log.info("[RealCoords] Java hook first clientbound packet: " + packet.getClass().getName());
            }
            if (message instanceof Packet packet) {
                Packet transformed = transform.clientbound(packet);
                JavaOriginState.Snapshot change = transform.takeOriginChange();
                if (change != null && log != null) {
                    log.info("[RealCoords] Java origin changed: offsetX=" + change.offsetX()
                            + ", offsetZ=" + change.offsetZ());
                }
                super.channelRead(ctx, transformed);
                if (transform.takeDeathPositionWarning() && log != null) {
                    log.warning("[RealCoords] lastDeathPos remains outside the local origin radius; "
                            + "Geyser may still lose precision for this distant death marker");
                }
                JavaPacketTransform.DeathPositionUpdate deathUpdate = transform.takeDeathPositionUpdate();
                if (deathUpdate != null && geyserSession != null) {
                    geyserSession.getTickEventLoop().execute(() -> {
                        try {
                            SessionPlayerEntity player = geyserSession.getPlayerEntity();
                            player.setLastDeathPosition(deathUpdate.position());
                            player.updateBedrockMetadata();
                        } catch (Throwable throwable) {
                            if (log != null) log.error("[RealCoords] failed to refresh shifted death position", throwable);
                        }
                    });
                }
                return;
            }
            super.channelRead(ctx, message);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) throws Exception {
            if (message instanceof Packet packet && firstServerbound.compareAndSet(false, true)) {
                if (log != null) log.info("[RealCoords] Java hook first serverbound packet: " + packet.getClass().getName());
            }
            if (message instanceof Packet packet) {
                if (syntheticResetGate.shouldSuppress(packet)) {
                    if (!syntheticResetGate.active()) cancelSyntheticResetTimeout();
                    promise.setSuccess();
                    return;
                }
                Packet transformed = transform.serverbound(packet);
                if (transformed != null) {
                    super.write(ctx, transformed, promise);
                } else {
                    promise.setSuccess();
                    long now = System.currentTimeMillis();
                    if (now - lastGuardWarningMs >= 5000) {
                        lastGuardWarningMs = now;
                        if (log != null) log.warning("[RealCoords] dropped an implausible Java serverbound player movement packet");
                    }
                }
                return;
            }
            super.write(ctx, message, promise);
        }
    }
}
