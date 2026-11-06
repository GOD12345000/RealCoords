package dev.realcoords;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.data.GameRuleData;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.geysermc.geyser.api.extension.ExtensionLogger;
import org.geysermc.geyser.session.GeyserSession;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Thin Bedrock-layer bridge used only in Java-origin mode.
 * It does not rewrite coordinates: JavaPacketTransform has already made Geyser's
 * internal coordinates local. This hook only tracks the real position for the
 * ActionBar and preserves the gamerule/Java ActionBar behavior.
 */
final class JavaBedrockAuxHook extends ChannelDuplexHandler {
    private final GeyserSession session;
    private final ShiftConfig config;
    private final JavaOriginState origin;
    private final ExtensionLogger log;
    private static final Set<String> WATCH = Set.of(
            "UpdateSubChunkBlocksPacket", "SubChunkPacket", "LevelEventGenericPacket",
            "MapItemDataPacket", "PositionTrackingDBServerBroadcastPacket", "AddPaintingPacket");
    private final Set<String> warned = new HashSet<>();
    private final Set<BedrockPacket> own = Collections.newSetFromMap(new IdentityHashMap<>());
    private Channel channel;
    private ScheduledFuture<?> actionBarTask;
    private long localEntityId = Long.MIN_VALUE;
    private boolean havePosition;
    private double realX, realY, realZ;
    private float yaw;
    private int dimension;
    private String javaActionBar;
    private long javaActionBarAt;

    private JavaBedrockAuxHook(GeyserSession session, ShiftConfig config,
                               JavaOriginState origin, ExtensionLogger log) {
        this.session = session;
        this.config = config;
        this.origin = origin;
        this.log = log;
    }

    static void install(GeyserSession session, ShiftConfig config,
                        JavaOriginState origin, ExtensionLogger log) {
        Channel channel = session.getUpstream().getSession().getPeer().getChannel();
        JavaBedrockAuxHook hook = new JavaBedrockAuxHook(session, config, origin, log);
        ChannelPipeline pipeline = channel.pipeline();
        ChannelHandlerContext peer = pipeline.context(BedrockPeer.class);
        if (peer == null) {
            log.error("[RealCoords] Java mode: BedrockPeer not found; ActionBar bridge is disabled");
            return;
        }
        pipeline.addBefore(peer.name(), "realcoords-java-bedrock-aux", hook);
        hook.channel = channel;
        if (config.abEnabled) {
            hook.actionBarTask = channel.eventLoop().scheduleAtFixedRate(() -> {
                try {
                    hook.sendActionBar();
                } catch (Throwable t) {
                    log.error("[RealCoords] Java mode ActionBar update failed", t);
                }
            }, 2000, config.abIntervalMs, TimeUnit.MILLISECONDS);
        }
        channel.closeFuture().addListener(ignored -> hook.close());
        if (config.debug) log.info("[RealCoords] Java-mode Bedrock auxiliary hook installed; pipeline=" + pipeline.names());
    }

    private void close() {
        if (actionBarTask != null) actionBarTask.cancel(false);
        own.clear();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
        each(message, this::inbound);
        super.channelRead(ctx, message);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) throws Exception {
        each(message, this::outbound);
        super.write(ctx, message, promise);
    }

    private void inbound(BedrockPacketWrapper wrapper) {
        BedrockPacket packet = wrapper.getPacket();
        if (packet instanceof PlayerAuthInputPacket input) {
            update(input.getPosition(), input.getRotation());
        } else if (packet instanceof MovePlayerPacket move) {
            update(move.getPosition(), move.getRotation());
        }
    }

    private void outbound(BedrockPacketWrapper wrapper) {
        BedrockPacket packet = wrapper.getPacket();
        if (packet == null || own.remove(packet)) return;
        String packetName = packet.getClass().getSimpleName();
        if (WATCH.contains(packetName) && warned.add(packetName)) {
            log.warning("[RealCoords] Java mode WATCH: Geyser emitted " + packetName
                    + "; verify its world coordinates are derived from transformed Java packets");
        }

        if (packet instanceof StartGamePacket start) {
            localEntityId = start.getRuntimeEntityId();
            dimension = start.getDimensionId();
            hideCoordinates(start.getGamerules());
        } else if (packet instanceof GameRulesChangedPacket changed) {
            hideCoordinates(changed.getGameRules());
        } else if (packet instanceof MovePlayerPacket move
                && move.getRuntimeEntityId() == localEntityId) {
            update(move.getPosition(), move.getRotation());
        } else if (packet instanceof RespawnPacket respawn) {
            if (respawn.getPosition() != null) update(respawn.getPosition(), null);
        } else if (packet instanceof ChangeDimensionPacket change) {
            dimension = change.getDimension();
            if (change.getPosition() != null) update(change.getPosition(), null);
        } else if (packet instanceof SetTitlePacket title
                && config.abEnabled && config.abMergeJava
                && title.getType() == SetTitlePacket.Type.ACTIONBAR) {
            javaActionBar = title.getText();
            javaActionBarAt = System.currentTimeMillis();
            if (havePosition) title.setText(formatActionBar(javaActionBarAt));
        }
    }

    private void update(Vector3f local, Vector3f rotation) {
        if (local == null) return;
        JavaOriginState.Offsets offsets = origin.offsets();
        realX = local.getX() + offsets.x();
        realY = local.getY() - config.eyeHeight;
        realZ = local.getZ() + offsets.z();
        if (rotation != null) yaw = rotation.getY();
        havePosition = true;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void hideCoordinates(List rules) {
        if (!config.abHideVanilla || rules == null) return;
        rules.removeIf(value -> value instanceof GameRuleData<?> rule
                && "showcoordinates".equalsIgnoreCase(rule.getName()));
        rules.add(new GameRuleData<>("showcoordinates", false));
    }

    private void sendActionBar() {
        if (!havePosition || localEntityId == Long.MIN_VALUE || channel == null || !channel.isActive()) return;
        SetTitlePacket title = new SetTitlePacket();
        title.setType(SetTitlePacket.Type.ACTIONBAR);
        title.setText(formatActionBar(System.currentTimeMillis()));
        title.setFadeInTime(0);
        title.setStayTime(20);
        title.setFadeOutTime(0);
        title.setXuid("");
        title.setPlatformOnlineId("");
        title.setFilteredTitleText("");
        own.add(title);
        session.sendUpstreamPacket(title);
    }

    private String formatActionBar(long now) {
        JavaOriginState.Offsets offsets = origin.offsets();
        String result = config.abFormat
                .replace("{x}", number(realX))
                .replace("{y}", number(realY))
                .replace("{z}", number(realZ))
                .replace("{facing}", facing())
                .replace("{dim}", dimension == 1 ? "nether" : dimension == 2 ? "end" : "overworld")
                .replace("{offx}", Integer.toString(offsets.x()))
                .replace("{offz}", Integer.toString(offsets.z()));
        if (config.abMergeJava && javaActionBar != null
                && now - javaActionBarAt < config.abJavaKeepMs) {
            result += config.abSep + javaActionBar;
        }
        return result;
    }

    private String number(double value) {
        return String.format(Locale.ROOT, config.abNumber, value);
    }

    private String facing() {
        return config.facing[Math.floorMod(Math.round(yaw / 90f), 4)];
    }

    private static void each(Object message, java.util.function.Consumer<BedrockPacketWrapper> consumer) {
        if (message instanceof BedrockBatchWrapper batch) {
            for (BedrockPacketWrapper wrapper : batch.getPackets()) consumer.accept(wrapper);
        } else if (message instanceof BedrockPacketWrapper wrapper) {
            consumer.accept(wrapper);
        }
    }
}
