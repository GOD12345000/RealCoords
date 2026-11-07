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
import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;

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
        private final TransitionDeferralState deferralState = new TransitionDeferralState();
        private ChannelHandlerContext channelContext;
        private ScheduledFuture<?> syntheticResetTimeout;
        private ScheduledFuture<?> replayBatchTask;
        private ScheduledFuture<?> replayTimeout;
        private ReoriginReplayPlan activeReplayPlan;
        private LiveUpdateBuffer preparingLiveUpdates;
        private boolean syntheticResetPendingReplay;
        private long bedrockSpawnWaitDeadlineMs;
        private int nextSyntheticTeleportId = Integer.MIN_VALUE;

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
            if (activeReplayPlan != null) activeReplayPlan.abort();
            activeReplayPlan = null;
            cancelReplayTasks();
            channelContext = null;
        }

        boolean beginSyntheticReset(int reservedTeleportId) {
            ChannelHandlerContext ctx = channelContext;
            if (ctx == null || !ctx.executor().inEventLoop()) {
                throw new IllegalStateException("Synthetic reset must begin on the downstream channel event loop");
            }
            if (activeReplayPlan != null || syntheticResetPendingReplay) return false;
            if (!syntheticResetGate.begin(reservedTeleportId, 2)) return false;
            preparingLiveUpdates = new LiveUpdateBuffer(transform.liveUpdateBudgetBytes(), 4096);
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
            if (log != null) log.error("[RealCoords] replay failed; closing downstream session to avoid partial world state", exception);
            plan.abort();
            if (plan == activeReplayPlan) activeReplayPlan = null;
            cancelReplayTasks();
            syntheticResetPendingReplay = false;
            bedrockSpawnWaitDeadlineMs = 0;
            ctx.close();
        }

        void abortReplayPlan(ChannelHandlerContext ctx, ReoriginReplayPlan plan) {
            if (!ctx.executor().inEventLoop()) {
                throw new IllegalStateException("Replay abort must run on the downstream channel event loop");
            }
            if (plan == activeReplayPlan) {
                plan.abort();
                activeReplayPlan = null;
                cancelReplayTasks();
                ctx.close();
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
        public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
            if (message instanceof Packet packet && firstClientbound.compareAndSet(false, true)) {
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
                    if (log != null) log.warning("[RealCoords] unable to queue a real player position during re-origin; closing session to avoid stale teleport state");
                    ctx.close();
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
                    if (log != null) log.warning("[RealCoords] live world update buffer overflowed or could not snapshot a packet; closing session to avoid stale replay state");
                    ctx.close();
                    return;
                }
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

        private void observeDeferralPacket(Packet packet) {
            if (channelContext != null && !channelContext.executor().inEventLoop()) {
                throw new IllegalStateException("deferral state accessed outside the Java handler event loop");
            }
            String name = packet.getClass().getSimpleName();
            try {
                if (name.equals("ClientboundLoginPacket")) {
                    Object entityId = packet.getClass().getMethod("getEntityId").invoke(packet);
                    if (entityId instanceof Number number) deferralState.initialize(number.intValue());
                } else if (name.equals("ClientboundRespawnPacket")) {
                    deferralState.resetForRespawn();
                } else if (name.equals("ClientboundOpenScreenPacket")
                        || name.equals("ClientboundOpenHorseScreenPacket")) {
                    deferralState.observeContainerOpened();
                } else if (name.equals("ClientboundContainerClosePacket")) {
                    deferralState.observeContainerClosed();
                } else if (name.equals("ClientboundSetPassengersPacket")) {
                    Object vehicle = packet.getClass().getMethod("getEntityId").invoke(packet);
                    Object passengers = packet.getClass().getMethod("getPassengerIds").invoke(packet);
                    if (vehicle instanceof Number v && passengers instanceof int[] ids) {
                        deferralState.observePassengers(v.intValue(), ids);
                    }
                } else if (name.equals("ClientboundSetEntityDataPacket")) {
                    Object entity = packet.getClass().getMethod("getEntityId").invoke(packet);
                    Object metadata = packet.getClass().getMethod("getMetadata").invoke(packet);
                    if (entity instanceof Number e && metadata instanceof Object[] entries) {
                        for (Object entry : entries) {
                            if (entry == null) continue;
                            Object id = entry.getClass().getMethod("getId").invoke(entry);
                            Object value = entry.getClass().getMethod("getValue").invoke(entry);
                            if (id instanceof Number metadataId) {
                                deferralState.observePlayerMetadata(e.intValue(), metadataId.intValue(), value);
                            }
                        }
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                if (log != null) log.warning("[RealCoords] unable to observe deferral packet "
                        + name + ": " + exception.getClass().getSimpleName());
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
                    ReoriginReplayPlan scheduledPlan = plan;
                    replayTimeout = ctx.executor().schedule(() -> {
                        if (activeReplayPlan == scheduledPlan) {
                            if (log != null) log.warning("[RealCoords] replay transaction timed out; closing downstream session");
                            failReplay(ctx, scheduledPlan, new IllegalStateException("replay transaction timeout"));
                        }
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
                if (log != null) log.error("[RealCoords] unable to start replay transaction; closing downstream session", exception);
                ctx.close();
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
                    case LIVE_UPDATES -> replayLiveUpdates(ctx, plan, 256);
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
            if (log != null) log.info("[RealCoords] live re-origin replay completed; movement unfrozen");
        }

        private void cancelReplayTasks() {
            ScheduledFuture<?> batch = replayBatchTask;
            replayBatchTask = null;
            if (batch != null) batch.cancel(false);
            ScheduledFuture<?> timeout = replayTimeout;
            replayTimeout = null;
            if (timeout != null) timeout.cancel(false);
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
            if (message instanceof Packet packet && firstServerbound.compareAndSet(false, true)) {
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
                // Geyser has already confirmed this backend teleport in Bedrock
                // coordinates. Forward the acknowledgement unchanged; only the
                // synthetic-reset gate above may consume a synthetic confirmation.
                if (packet instanceof ServerboundAcceptTeleportationPacket) {
                    super.write(ctx, packet, promise);
                    return;
                }
                if (isMovementPacket(packet)
                        && (syntheticResetGate.active() || syntheticResetPendingReplay)) {
                    promise.setSuccess();
                    return;
                }
                if (isMovementPacket(packet) && activeReplayPlan != null) {
                    transform.observeMovementDuringReplay(packet);
                    promise.setSuccess();
                    return;
                }
                Packet transformed = transform.serverbound(packet, !deferralState.shouldDefer());
                JavaOriginState.Snapshot movementOriginChange = transform.takeOriginChange();
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
