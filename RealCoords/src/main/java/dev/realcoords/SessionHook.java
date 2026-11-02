package dev.realcoords;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.geysermc.geyser.api.extension.ExtensionLogger;
import org.geysermc.geyser.session.GeyserSession;

import java.util.function.Consumer;

/**
 * 掛在該玩家 Bedrock 連線的 Netty 管線上，位置在封包編解碼器之後、BedrockPeer 之前：
 * 往客戶端的封包在編碼前經過這裡，來自客戶端的封包在 Geyser 處理前經過這裡。
 */
final class SessionHook extends ChannelDuplexHandler {
    private final ShiftSession s;

    private SessionHook(ShiftSession s) {
        this.s = s;
    }

    static void install(GeyserSession gs, ShiftConfig cfg, ExtensionLogger log) {
        Channel ch = gs.getUpstream().getSession().getPeer().getChannel();
        ShiftSession session = new ShiftSession(gs, ch, cfg, log);
        SessionHook hook = new SessionHook(session);

        ChannelPipeline pl = ch.pipeline();
        ChannelHandlerContext peer = pl.context(BedrockPeer.class);
        if (peer != null) {
            pl.addBefore(peer.name(), "realcoords-shift", hook);
        } else {
            log.warning("[RealCoords] 找不到 BedrockPeer，改掛在管線尾端，轉換可能不會生效");
            pl.addLast("realcoords-shift", hook);
        }
        session.start();
        ch.closeFuture().addListener(f -> session.close());
        if (cfg.debug) log.info("[RealCoords] pipeline: " + pl.names());
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            each(msg, s::inbound);
        } catch (Throwable t) {
            s.fail("inbound", t);
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        try {
            each(msg, s::outbound);
        } catch (Throwable t) {
            s.fail("outbound", t);
        }
        ctx.write(msg, promise);
    }

    private static void each(Object msg, Consumer<BedrockPacketWrapper> c) {
        if (msg instanceof BedrockBatchWrapper batch) {
            for (BedrockPacketWrapper w : batch.getPackets()) c.accept(w);
        } else if (msg instanceof BedrockPacketWrapper w) {
            c.accept(w);
        }
    }
}
