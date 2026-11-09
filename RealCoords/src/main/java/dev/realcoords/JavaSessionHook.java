package dev.realcoords;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import net.kyori.adventure.text.Component;
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
import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Pose;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetPassengersPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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

            JavaPacketTransformHandler handler = new JavaPacketTransformHandler(new JavaPacketTransform(origin, config.maxJump, config), geyserSession, log, config.debug);
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
        private final boolean debugLog;
        private final AtomicBoolean firstClientbound = new AtomicBoolean();
        private final AtomicBoolean firstServerbound = new AtomicBoolean();
        private long lastGuardWarningMs;
        private int coordinateDiagnosticsRemaining;
        private final Map<String, long[]> coordinatePacketCounts = new LinkedHashMap<>();

        private final JavaPacketTransform transform;
        private final GeyserSession geyserSession;
        private final SyntheticResetGate syntheticResetGate = new SyntheticResetGate();
        private final TransitionDeferralState deferralState = new TransitionDeferralState();
        private ChannelHandlerContext channelContext;
        private ScheduledFuture<?> syntheticResetTimeout;
        private ScheduledFuture<?> transitionTimeout;
        private final ReoriginTransition transition = new ReoriginTransition();
        private final TransitionTimingPolicy timingPolicy;
        private boolean transitionDisconnectNotified;
        private ScheduledFuture<?> replayBatchTask;
        private ScheduledFuture<?> replayTimeout;
        private ReoriginReplayPlan activeReplayPlan;
        private LiveUpdateBuffer preparingLiveUpdates;
        private boolean syntheticResetPendingReplay;
        private long bedrockSpawnWaitDeadlineMs;
        private int nextSyntheticTeleportId = Integer.MIN_VALUE;

        JavaPacketTransformHandler(JavaPacketTransform transform, ExtensionLogger log) {
            this(transform, null, log, false);
        }

        JavaPacketTransformHandler(JavaPacketTransform transform, GeyserSession geyserSession, ExtensionLogger log) {
            this(transform, geyserSession, log, false);
        }

        JavaPacketTransformHandler(JavaPacketTransform transform, GeyserSession geyserSession, ExtensionLogger log, boolean debugLog) {
            this.transform = transform;
            this.geyserSession = geyserSession;
            this.log = log;
            this.debugLog = debugLog;
            this.timingPolicy = new TransitionTimingPolicy(transform.transitionCooldownTicks(), transform.transitionGraceTicks());
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            channelContext = ctx;
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            if (transition.state() != ReoriginTransition.State.NORMAL && transition.state() != ReoriginTransition.State.FAILED) {
                transition.fail("handler removed during transition");
            }
            cancelTransitionTimeout();
            abortSyntheticReset();
            if (activeReplayPlan != null) activeReplayPlan.abort();
            activeReplayPlan = null;
            cancelReplayTasks();
            channelContext = null;
        }

        TransitionDeferralState deferralState() { return deferralState; }

        private void beginCoordinateDiagnosticWindow() {
            if (debugLog) coordinateDiagnosticsRemaining = 20;
        }

        private void recordCoordinatePacket(String direction, Packet original, Packet transformed,
                                             int offsetX, int offsetZ) {
            if (!debugLog) return;
            Optional<String> before = PacketCoordinateInspector.describe(original);
            if (before.isEmpty()) return;

            String packetName = original.getClass().getSimpleName();
            String key = direction + "/" + packetName;
            long[] counts = coordinatePacketCounts.computeIfAbsent(key, ignored -> new long[3]);
            String after = transformed == null
                    ? "<dropped>"
                    : PacketCoordinateInspector.describe(transformed).orElse("<no coordinate payload>");
            boolean converted = transformed != null && !before.get().equals(after);
            if (converted) {
                counts[0]++;
            } else if (transformed == null) {
                counts[2]++;
            } else {
                counts[1]++;
            }

            if (coordinateDiagnosticsRemaining > 0) {
                if (log != null) {
                    String operation = direction.equals("clientbound") ? "subtract" : "add";
                    log.info("[RealCoords] coordinate packet #" + (21 - coordinateDiagnosticsRemaining)
                            + " direction=" + direction
                            + " type=" + original.getClass().getName()
                            + " before={" + before.get() + "}"
                            + " after={" + after + "}"
                            + " offsetX=" + offsetX + " offsetZ=" + offsetZ
                            + " operation=" + operation
                            + " result=" + (converted ? "converted" : transformed == null ? "dropped" : "unchanged"));
                }
                coordinateDiagnosticsRemaining--;
            }
        }

        private void logCoordinatePacketCounts() {
            if (!debugLog || log == null || coordinatePacketCounts.isEmpty()) return;
            StringBuilder summary = new StringBuilder("[RealCoords] coordinate packet totals:");
            for (Map.Entry<String, long[]> entry : coordinatePacketCounts.entrySet()) {
                long[] counts = entry.getValue();
                summary.append(" ").append(entry.getKey())
                        .append("{converted=").append(counts[0])
                        .append(",unchanged=").append(counts[1])
                        .append(",dropped=").append(counts[2]).append("}");
            }
            log.info(summary.toString());
        }

        private void cancelTransitionTimeout() {
            ScheduledFuture<?> timeout = transitionTimeout;
            transitionTimeout = null;
            if (timeout != null) timeout.cancel(false);
        }

        private void scheduleTransitionTimeout(ChannelHandlerContext ctx) {
            cancelTransitionTimeout();
            long timeoutMillis = Math.max(1L, transform.transitionTimeoutMillis());
            transitionTimeout = ctx.executor().schedule(() -> failTransition(ctx, "transition timed out during " + transition.state()), timeoutMillis, TimeUnit.MILLISECONDS);
        }

        private void dispatchDisconnect(ChannelHandlerContext ctx, String message) {
            if (geyserSession == null || transitionDisconnectNotified) {
                if (ctx != null) ctx.close();
                return;
            }
            transitionDisconnectNotified = true;
            geyserSession.getTickEventLoop().execute(() -> {
                // Start the explicit Bedrock disconnect before closing the Java channel.
                geyserSession.disconnect(Component.text(message));
                if (ctx != null) ctx.executor().execute(ctx::close);
            });
        }

        private void failTransition(ChannelHandlerContext ctx, String reason) {
            if (transition.state() == ReoriginTransition.State.NORMAL || transition.state() == ReoriginTransition.State.FAILED) {
                dispatchDisconnect(ctx, "RealCoords could not safely complete the coordinate transition. Please reconnect.");
                return;
            }
            transition.fail(reason);
            cancelTransitionTimeout();
            cancelSyntheticResetTimeout();
            syntheticResetGate.abort();
            syntheticResetPendingReplay = false;
            if (preparingLiveUpdates != null) preparingLiveUpdates.clear();
            preparingLiveUpdates = null;
            if (activeReplayPlan != null) activeReplayPlan.abort();
            activeReplayPlan = null;
            cancelReplayTasks();
            bedrockSpawnWaitDeadlineMs = 0;
            if (log != null) log.error("[RealCoords] transition failed at " + transition.failure().failedAt() + ": " + transition.failure().reason());
            dispatchDisconnect(ctx, "RealCoords could not safely complete the coordinate transition. Please reconnect.");
        }


        ReoriginTransition.State transitionState() { return transition.state(); }

        ReoriginTransition.Failure transitionFailure() { return transition.failure(); }

        boolean beginSyntheticReset(int reservedTeleportId) {
            ChannelHandlerContext ctx = channelContext;
            if (ctx == null || !ctx.executor().inEventLoop()) {
                throw new IllegalStateException("Synthetic reset must begin on the downstream channel event loop");
            }
            if (activeReplayPlan != null || syntheticResetPendingReplay) return false;
            if (!syntheticResetGate.begin(reservedTeleportId, 2)) return false;
            transition.enter(ReoriginTransition.State.PREPARE);
            transition.enter(ReoriginTransition.State.RESET);
            scheduleTransitionTimeout(ctx);
            preparingLiveUpdates = new LiveUpdateBuffer(transform.liveUpdateBudgetBytes(), 4096);
            cancelSyntheticResetTimeout();
            syntheticResetTimeout = ctx.executor().schedule(() -> {
                failTransition(ctx, "synthetic reset acknowledgement timed out");
            }, transform.syntheticAckTimeoutMillis(), TimeUnit.MILLISECONDS);
            return true;
        }

        void abortSyntheticReset() {
            boolean wasActive = syntheticResetGate.active();
            syntheticResetGate.abort();
            syntheticResetPendingReplay = false;
            if (preparingLiveUpdates != null) preparingLiveUpdates.clear();
            preparingLiveUpdates = null;
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
            syntheticResetPendingReplay = true;
            try {
                for (Packet respawn : respawns) ctx.fireChannelRead(respawn);
                ctx.fireChannelRead(position);
                return respawns.size() + 1;
            } catch (RuntimeException exception) {
                abortSyntheticReset();
                throw exception;
            }
        }

        ReoriginReplayPlan prepareReplayPlan(int centerChunkX, int centerChunkZ,
                                                  double centerX, double centerZ, double entityRadius) {
            if (channelContext == null || !channelContext.executor().inEventLoop()) {
                throw new IllegalStateException("Replay snapshot must be prepared on the downstream channel event loop");
            }
            if (activeReplayPlan != null) throw new IllegalStateException("A replay plan is already active");
            JavaPacketTransform.ReplaySnapshot snapshot = transform.prepareReplaySnapshot(
                    centerChunkX, centerChunkZ, centerX, centerZ, entityRadius);
            LiveUpdateBuffer liveUpdates = preparingLiveUpdates;
            if (liveUpdates == null) {
                liveUpdates = new LiveUpdateBuffer(transform.liveUpdateBudgetBytes(), 4096);
            }
            preparingLiveUpdates = null;
            activeReplayPlan = new ReoriginReplayPlan(snapshot.chunks(), snapshot.entities(), snapshot.globalState(),
                    liveUpdates);
            return activeReplayPlan;
        }

        int replayChunkBatch(ChannelHandlerContext ctx, ReoriginReplayPlan plan, int limit) {
            requireReplayEventLoop(ctx, plan);
            try {
                var packets = transform.replayChunkBatch(plan.nextChunkBatch(limit));
                for (Packet packet : packets) ctx.fireChannelRead(packet);
                return packets.size();
            } catch (RuntimeException exception) {
                failReplay(ctx, plan, exception);
                return 0;
            }
        }

        int replayEntityBatch(ChannelHandlerContext ctx, ReoriginReplayPlan plan, int limit) {
            requireReplayEventLoop(ctx, plan);
            try {
                var packets = transform.replayEntityBatch(plan.nextEntityBatch(limit));
                for (Packet packet : packets) ctx.fireChannelRead(packet);
                return packets.size();
            } catch (RuntimeException exception) {
                failReplay(ctx, plan, exception);
                return 0;
            }
        }

        int replayGlobalState(ChannelHandlerContext ctx, ReoriginReplayPlan plan) {
            requireReplayEventLoop(ctx, plan);
            try {
                var packets = transform.replayGlobalBatch(plan.finishGlobalState());
                for (Packet packet : packets) ctx.fireChannelRead(packet);
                return packets.size();
            } catch (RuntimeException exception) {
                failReplay(ctx, plan, exception);
                return 0;
            }
        }

        int replayLiveUpdates(ChannelHandlerContext ctx, ReoriginReplayPlan plan, int limit) {
            requireReplayEventLoop(ctx, plan);
            try {
                var buffered = plan.nextLiveUpdateBatch(limit);
                var packets = transform.replayBufferedUpdates(buffered);
                for (Packet packet : packets) ctx.fireChannelRead(packet);
                if (plan.aborted()) {
                    activeReplayPlan = null;
                    ctx.close();
                } else if (plan.complete()) {
                    finishReplay(ctx, plan);
                }
                return packets.size();
            } catch (RuntimeException exception) {
                failReplay(ctx, plan, exception);
                return 0;
            }
        }

        private void requireReplayEventLoop(ChannelHandlerContext ctx, ReoriginReplayPlan plan) {
            if (!ctx.executor().inEventLoop()) {
                throw new IllegalStateException("Replay must run on the downstream channel event loop");
            }
            if (plan != activeReplayPlan) throw new IllegalStateException("Replay plan is not active on this session");
        }

        private void failReplay(ChannelHandlerContext ctx, ReoriginReplayPlan plan, RuntimeException exception) {
            if (plan != null) plan.abort();
            failTransition(ctx, "replay exception: " + exception.getClass().getSimpleName() + (exception.getMessage() == null ? "" : ": " + exception.getMessage()));
        }

        void abortReplayPlan(ChannelHandlerContext ctx, ReoriginReplayPlan plan) {
            if (!ctx.executor().inEventLoop()) {
                throw new IllegalStateException("Replay abort must run on the downstream channel event loop");
            }
            if (plan == activeReplayPlan) {
                plan.abort();
                failTransition(ctx, "replay plan explicitly aborted");
            }
        }



        int replayCachedChunks(ChannelHandlerContext ctx, int centerChunkX, int centerChunkZ, int limit) {
            return replayCachedChunks(ctx, centerChunkX, centerChunkZ, 0, limit);
        }

        int replayCachedChunks(ChannelHandlerContext ctx, int centerChunkX, int centerChunkZ,
                               int startIndex, int limit) {
            if (!ctx.executor().inEventLoop()) {
                throw new IllegalStateException("Cached chunk replay must run on the downstream channel event loop");
            }
            var packets = transform.replayCachedChunks(centerChunkX, centerChunkZ, startIndex, limit);
            for (Packet packet : packets) {
                ctx.fireChannelRead(packet);
            }
            return packets.size();
        }

        int replayCachedEntities(ChannelHandlerContext ctx, double centerX, double centerZ,
                                 double radiusBlocks, int limit) {
            if (!ctx.executor().inEventLoop()) {
                throw new IllegalStateException("Cached entity replay must run on the downstream channel event loop");
            }
            var packets = transform.replayCachedEntities(centerX, centerZ, radiusBlocks, limit);
            for (Packet packet : packets) ctx.fireChannelRead(packet);
            return packets.size();
        }

        int replayGlobalState(ChannelHandlerContext ctx) {
            if (!ctx.executor().inEventLoop()) {
                throw new IllegalStateException("Global state replay must run on the downstream channel event loop");
            }
            var packets = transform.replayGlobalState();
            for (Packet packet : packets) ctx.fireChannelRead(packet);
            return packets.size();
        }

        int cachedEntityCount() {
            return transform.cachedEntityCount();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (transition.state() != ReoriginTransition.State.NORMAL && transition.state() != ReoriginTransition.State.FAILED) {
                failTransition(ctx, "downstream disconnected during transition");
            } else {
                cancelTransitionTimeout();
                cancelSyntheticResetTimeout();
                cancelReplayTasks();
                if (activeReplayPlan != null) activeReplayPlan.abort();
                activeReplayPlan = null;
                if (preparingLiveUpdates != null) preparingLiveUpdates.clear();
                preparingLiveUpdates = null;
            }
            logCoordinatePacketCounts();
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            if (transition.state() != ReoriginTransition.State.NORMAL && transition.state() != ReoriginTransition.State.FAILED) {
                failTransition(ctx, "exception during transition: " + cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage()));
                return;
            }
            super.exceptionCaught(ctx, cause);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
            if (debugLog && message instanceof Packet packet && firstClientbound.compareAndSet(false, true)) {
                if (log != null) log.info("[RealCoords] Java hook first clientbound packet: " + packet.getClass().getName());
            }
            if (message instanceof Packet packet) {
                observeDeferralPacket(packet);
                if (isImmediatePassThroughPacket(packet)) {
                    super.channelRead(ctx, packet);
                    return;
                }
                // A real backend position update is part of the live transition stream.
                // Keep its raw packet until replay drains so it is transformed exactly once
                // with the final origin. Synthetic positions are injected downstream via
                // ctx.fireChannelRead and never re-enter this inbound handler.
                if ((syntheticResetGate.active() || syntheticResetPendingReplay || activeReplayPlan != null)
                        && packet instanceof ClientboundPlayerPositionPacket realPosition) {
                    boolean buffered = false;
                    if (activeReplayPlan != null) {
                        buffered = activeReplayPlan.bufferLiveUpdate(realPosition);
                    } else if (preparingLiveUpdates != null) {
                        buffered = preparingLiveUpdates.offer(realPosition);
                    }
                    if (buffered) return;

                    if (activeReplayPlan != null) activeReplayPlan.abort();
                    activeReplayPlan = null;
                    if (preparingLiveUpdates != null) preparingLiveUpdates.clear();
                    preparingLiveUpdates = null;
                    cancelReplayTasks();
                    failTransition(ctx, "live player-position queue overflow");
                    return;
                }
                if ((syntheticResetGate.active() || activeReplayPlan != null) && isWorldResetPacket(packet)) {
                    if (log != null) log.warning("[RealCoords] unexpected backend login/respawn during live re-origin; closing downstream session");
                    if (activeReplayPlan != null) activeReplayPlan.abort();
                    activeReplayPlan = null;
                    cancelReplayTasks();
                    ctx.close();
                    return;
                }
                if ((syntheticResetGate.active() || syntheticResetPendingReplay || activeReplayPlan != null)
                        && isReplaySensitive(packet)) {
                    boolean cachedAtArrival = false;
                    try {
                        transform.clientbound(packet);
                        transform.takeOriginChange();
                        cachedAtArrival = true;
                    } catch (RuntimeException exception) {
                        if (log != null) log.error("[RealCoords] unable to observe a live world update during PREPARE/REPLAY", exception);
                    }
                    boolean buffered = false;
                    if (cachedAtArrival && packet instanceof MinecraftPacket minecraftPacket) {
                        if (activeReplayPlan != null) {
                            buffered = activeReplayPlan.bufferLiveUpdate(minecraftPacket);
                        } else if (preparingLiveUpdates != null) {
                            buffered = preparingLiveUpdates.offer(minecraftPacket);
                        }
                    }
                    if (buffered) return;
                    if (activeReplayPlan != null) activeReplayPlan.abort();
                    activeReplayPlan = null;
                    if (preparingLiveUpdates != null) preparingLiveUpdates.clear();
                    preparingLiveUpdates = null;
                    cancelReplayTasks();
                    failTransition(ctx, "live world-update queue overflow or snapshot failure");
                    return;
                }
                Packet transformed = transform.clientbound(packet);
                JavaOriginState.Snapshot change = transform.takeOriginChange();
                if (change != null && change.changed()) {
                    beginCoordinateDiagnosticWindow();
                    if (debugLog && log != null) {
                        log.info("[RealCoords] Java origin changed: offsetX=" + change.offsetX()
                                + ", offsetZ=" + change.offsetZ());
                    }
                }
                recordCoordinatePacket("clientbound", packet, transformed,
                        transform.offsetX(), transform.offsetZ());
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

        private void observeDeferralPacket(Packet packet) {
            if (!channelContext.executor().inEventLoop()) {
                throw new IllegalStateException("deferral state accessed outside the Java handler event loop");
            }
            if (packet instanceof ClientboundLoginPacket login) {
                deferralState.initialize(login.getEntityId());
            } else if (packet instanceof ClientboundRespawnPacket) {
                // In 1.21.8, a dimension change is represented by Respawn.
                deferralState.resetForRespawn();
            } else if (packet instanceof ClientboundOpenScreenPacket) {
                deferralState.observeContainerOpened();
            } else if (packet instanceof ClientboundContainerClosePacket) {
                deferralState.observeContainerClosed();
            } else if (packet instanceof ClientboundSetPassengersPacket passengers) {
                deferralState.observePassengers(passengers.getEntityId(), passengers.getPassengerIds());
            } else if (packet instanceof ClientboundSetEntityDataPacket entityData) {
                for (EntityMetadata<?, ?> metadata : entityData.getMetadata()) {
                    if (metadata == null) {
                        deferralState.markObservationUnavailable();
                        continue;
                    }
                    Object value = metadata.getValue();
                    if (metadata.getId() == 6) {
                        if (value instanceof Pose pose) {
                            deferralState.observePlayerMetadata(entityData.getEntityId(), 6, pose);
                        } else {
                            deferralState.markObservationUnavailable();
                        }
                    } else if (metadata.getId() == 0) {
                        if (value instanceof Byte flags) {
                            deferralState.observePlayerMetadata(entityData.getEntityId(), 0, flags);
                        } else {
                            deferralState.markObservationUnavailable();
                        }
                    }
                }
            }
        }

        private int allocateSyntheticTeleportId() {
            int id = nextSyntheticTeleportId++;
            if (nextSyntheticTeleportId >= 0) nextSyntheticTeleportId = Integer.MIN_VALUE;
            return id;
        }

        private void startAutomaticReplay(ChannelHandlerContext ctx) {
            if (!ctx.executor().inEventLoop()) {
                ctx.executor().execute(() -> startAutomaticReplay(ctx));
                return;
            }
            try {
                ReoriginReplayPlan plan = activeReplayPlan;
                if (plan == null) {
                    double realX = transform.realX();
                    double realZ = transform.realZ();
                    int chunkX = (int) Math.floor(realX / 16.0);
                    int chunkZ = (int) Math.floor(realZ / 16.0);
                    plan = prepareReplayPlan(chunkX, chunkZ, realX, realZ,
                            transform.entityReplayRadius());
                    syntheticResetPendingReplay = false;
                    if (transition.state() == ReoriginTransition.State.RESET) transition.enter(ReoriginTransition.State.REPLAY);
                    ReoriginReplayPlan scheduledPlan = plan;
                    replayTimeout = ctx.executor().schedule(() -> {
                        if (activeReplayPlan == scheduledPlan) failTransition(ctx, "replay transaction timed out");
                    }, transform.replayTimeoutMillis(plan.chunkCount(), plan.entityPacketCount()), TimeUnit.MILLISECONDS);
                }
                if (geyserSession != null && !geyserSession.isSpawned()) {
                    long now = System.currentTimeMillis();
                    if (bedrockSpawnWaitDeadlineMs == 0) {
                        bedrockSpawnWaitDeadlineMs = now + transform.syntheticAckTimeoutMillis() * 2L;
                    }
                    if (now >= bedrockSpawnWaitDeadlineMs) {
                        failReplay(ctx, plan, new IllegalStateException("Geyser did not enter the spawned state after the synthetic teleport"));
                        return;
                    }
                    replayBatchTask = ctx.executor().schedule(() -> startAutomaticReplay(ctx), 50, TimeUnit.MILLISECONDS);
                    return;
                }
                bedrockSpawnWaitDeadlineMs = 0;
                scheduleReplayBatch(ctx, plan);
            } catch (RuntimeException exception) {
                failTransition(ctx, "unable to start replay: " + exception.getClass().getSimpleName()
                        + (exception.getMessage() == null ? "" : ": " + exception.getMessage()));
            }
        }

        private void scheduleReplayBatch(ChannelHandlerContext ctx, ReoriginReplayPlan plan) {
            if (!ctx.executor().inEventLoop()) {
                ctx.executor().execute(() -> scheduleReplayBatch(ctx, plan));
                return;
            }
            if (plan != activeReplayPlan || !ctx.channel().isActive()) return;
            try {
                switch (plan.stage()) {
                    case CHUNKS -> replayChunkBatch(ctx, plan, transform.replayBatchSize());
                    case ENTITIES -> replayEntityBatch(ctx, plan, transform.entityReplayBatchSize());
                    case GLOBAL_STATE -> replayGlobalState(ctx, plan);
                    case LIVE_UPDATES -> {
                        if (transition.state() == ReoriginTransition.State.REPLAY) transition.enter(ReoriginTransition.State.DRAIN);
                        replayLiveUpdates(ctx, plan, 256);
                    }
                    case COMPLETE -> {
                        finishReplay(ctx, plan);
                        return;
                    }
                    case ABORTED -> {
                        failReplay(ctx, plan, new IllegalStateException("replay plan aborted"));
                        return;
                    }
                }
                if (plan.complete()) {
                    finishReplay(ctx, plan);
                } else if (plan.aborted()) {
                    failReplay(ctx, plan, new IllegalStateException("replay plan aborted"));
                } else if (plan == activeReplayPlan) {
                    replayBatchTask = ctx.executor().schedule(
                            () -> scheduleReplayBatch(ctx, plan), 50, TimeUnit.MILLISECONDS);
                }
            } catch (RuntimeException exception) {
                failReplay(ctx, plan, exception);
            }
        }

        private void finishReplay(ChannelHandlerContext ctx, ReoriginReplayPlan plan) {
            if (plan != activeReplayPlan || !plan.complete()) return;
            activeReplayPlan = null;
            syntheticResetPendingReplay = false;
            preparingLiveUpdates = null;
            bedrockSpawnWaitDeadlineMs = 0;
            cancelReplayTasks();
            cancelTransitionTimeout();
            timingPolicy.transitionCompleted(System.nanoTime());
            if (transition.state() == ReoriginTransition.State.REPLAY) transition.enter(ReoriginTransition.State.DRAIN);
            if (transition.state() == ReoriginTransition.State.DRAIN) transition.enter(ReoriginTransition.State.NORMAL);
            if (debugLog && log != null) log.info("[RealCoords] live re-origin replay completed; movement unfrozen");
        }

        private void cancelReplayTasks() {
            ScheduledFuture<?> batch = replayBatchTask;
            replayBatchTask = null;
            if (batch != null) batch.cancel(false);
            ScheduledFuture<?> timeout = replayTimeout;
            replayTimeout = null;
            if (timeout != null) timeout.cancel(false);
        }

        private void logMovementRejection() {
            JavaPacketTransform.MovementRejection rejection = transform.takeMovementRejection();
            if (rejection == null) return;
            long now = System.currentTimeMillis();
            if (now - lastGuardWarningMs >= 5000) {
                lastGuardWarningMs = now;
                if (log != null) log.warning("[RealCoords] dropped serverbound movement: distance="
                        + rejection.distance() + " blocks, limit=" + rejection.limit() + " blocks");
            }
        }

        private static boolean isImmediatePassThroughPacket(Packet packet) {
            String name = packet.getClass().getSimpleName();
            return name.equals("ClientboundPingPacket")
                    || name.equals("ClientboundKeepAlivePacket")
                    || name.equals("ClientboundChunkBatchStartPacket")
                    || name.equals("ClientboundChunkBatchFinishedPacket")
                    || name.equals("ServerboundPongPacket")
                    || name.equals("ServerboundKeepAlivePacket")
                    || name.equals("ServerboundChunkBatchReceivedPacket");
        }

        private static boolean isMovementPacket(Packet packet) {
            String name = packet.getClass().getSimpleName();
            return name.startsWith("ServerboundMovePlayer")
                    || name.equals("ServerboundMoveVehiclePacket");
        }

        private static boolean isWorldResetPacket(Packet packet) {
            String name = packet.getClass().getSimpleName();
            return name.equals("ClientboundLoginPacket") || name.equals("ClientboundRespawnPacket")
                    || name.equals("ClientboundPlayerPositionPacket");
        }

        private static boolean isReplaySensitive(Packet packet) {
            String packageName = packet.getClass().getPackageName();
            String className = packet.getClass().getSimpleName();
            return packageName.contains(".clientbound.level")
                    || packageName.contains(".clientbound.entity")
                    || packageName.contains(".clientbound.scoreboard")
                    || className.equals("ClientboundChangeDifficultyPacket")
                    || className.equals("ClientboundBossEventPacket")
                    || className.equals("ClientboundGameRuleValuesPacket")
                    || className.equals("ClientboundPlayerInfoUpdatePacket")
                    || className.equals("ClientboundPlayerInfoRemovePacket");
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) throws Exception {
            if (debugLog && message instanceof Packet packet && firstServerbound.compareAndSet(false, true)) {
                if (log != null) log.info("[RealCoords] Java hook first serverbound packet: " + packet.getClass().getName());
            }
            if (message instanceof Packet packet) {
                if (isImmediatePassThroughPacket(packet)) {
                    super.write(ctx, packet, promise);
                    return;
                }
                if (syntheticResetGate.shouldSuppress(packet)) {
                    if (!syntheticResetGate.active()) {
                        cancelSyntheticResetTimeout();
                        if (syntheticResetPendingReplay) {
                            startAutomaticReplay(ctx);
                        }
                    }
                    promise.setSuccess();
                    return;
                }
                // Geyser 26.x carries the confirmed player position in this packet.
                // Keep synthetic reset acknowledgements gated above, but explicitly restore
                // the real coordinates before forwarding a genuine Geyser confirmation.
                if (packet instanceof ServerboundAcceptTeleportationPacket) {
                    Packet transformed = transform.serverbound(packet, false,
                            transform.maxServerboundJump());
                    recordCoordinatePacket("serverbound", packet, transformed,
                            transform.offsetX(), transform.offsetZ());
                    super.write(ctx, transformed, promise);
                    return;
                }
                if ((transform.offsetX() != 0 || transform.offsetZ() != 0)
                        && PacketCoordinateInspector.hasUnrecognizedServerboundCoordinates(packet)) {
                    String coordinates = PacketCoordinateInspector.describe(packet).orElse("<unavailable>");
                    if (log != null) {
                        log.warning("[RealCoords] dropped unrecognized coordinate-bearing serverbound packet "
                                + packet.getClass().getName() + " coordinates={" + coordinates + "}"
                                + " offsetX=" + transform.offsetX() + " offsetZ=" + transform.offsetZ());
                    }
                    recordCoordinatePacket("serverbound", packet, null,
                            transform.offsetX(), transform.offsetZ());
                    promise.setSuccess();
                    return;
                }
                long nowNanos = System.nanoTime();
                boolean transitionActive = transition.state() != ReoriginTransition.State.NORMAL
                        && transition.state() != ReoriginTransition.State.FAILED;
                double jumpLimit = timingPolicy.movementJumpLimit(transitionActive, nowNanos,
                        transform.maxServerboundJump(), transform.transitionGraceMaxJump());
                if (isMovementPacket(packet)
                        && (syntheticResetGate.active() || syntheticResetPendingReplay)) {
                    transform.observeMovementDuringReplay(packet, jumpLimit);
                    logMovementRejection();
                    promise.setSuccess();
                    return;
                }
                if (isMovementPacket(packet) && activeReplayPlan != null) {
                    transform.observeMovementDuringReplay(packet, jumpLimit);
                    logMovementRejection();
                    promise.setSuccess();
                    return;
                }
                if (deferralState.shouldDefer() && isMovementPacket(packet)) {
                    if (!transform.observeMovementDuringReplay(packet, jumpLimit)) {
                        logMovementRejection();
                        promise.setSuccess();
                        return;
                    }
                    TransitionWalkFallback.Action fallback = transform.deferredWalkFallback(packet);
                    if (fallback == TransitionWalkFallback.Action.KICK) {
                        promise.setSuccess();
                        dispatchDisconnect(ctx, "RealCoords cannot safely defer re-origin while you continue walking. Please reconnect.");
                        return;
                    }
                    if (fallback == TransitionWalkFallback.Action.REORIGIN) {
                        promise.setSuccess();
                        if (!transform.forceDeferredReorigin(packet)) {
                            dispatchDisconnect(ctx, "RealCoords could not safely rebuild the nearby world. Please reconnect.");
                            return;
                        }
                        int forcedTeleportId = allocateSyntheticTeleportId();
                        if (injectSyntheticReset(ctx, forcedTeleportId) == 0) {
                            failTransition(ctx, "walk-fallback reorigin could not start replay");
                        }
                        return;
                    }
                }
                boolean allowOriginShift = !deferralState.shouldDefer() && !timingPolicy.cooldownActive(nowNanos);
                Packet transformed = transform.serverbound(packet, allowOriginShift, jumpLimit);
                JavaOriginState.Snapshot movementOriginChange = transform.takeOriginChange();
                if (movementOriginChange != null && movementOriginChange.changed()) {
                    beginCoordinateDiagnosticWindow();
                }
                recordCoordinatePacket("serverbound", packet, transformed,
                        transform.offsetX(), transform.offsetZ());
                if (movementOriginChange != null && movementOriginChange.changed()) {
                    promise.setSuccess();
                    int reservedTeleportId = allocateSyntheticTeleportId();
                    if (injectSyntheticReset(ctx, reservedTeleportId) == 0) {
                        if (log != null) log.warning("[RealCoords] unable to begin live re-origin reset; closing downstream session");
                        ctx.close();
                    }
                    return;
                }
                if (transformed != null) {
                    super.write(ctx, transformed, promise);
                } else {
                    promise.setSuccess();
                    logMovementRejection();
                }
                return;
            }
            super.write(ctx, message, promise);
        }
    }
}
