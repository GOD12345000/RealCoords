package dev.realcoords;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.network.session.NetworkSession;
import org.geysermc.mcprotocollib.network.packet.Packet;

/**
 * 在 Geyser 的 Java 下游連線上做座標轉換。
 *
 * Bedrock local float coordinates stay small while Geyser performs physics and
 * interaction validation. Only after Geyser has produced Java packets do we add
 * the floating-origin offset, using Java packet doubles.
 */
final class JavaSessionHook extends ChannelDuplexHandler {
    private final ShiftSession shift;

    private JavaSessionHook(ShiftSession shift) {
        this.shift = shift;
    }

    static void install(GeyserSession session, ShiftSession shift) {
        if (session.getDownstream() == null || session.getDownstream().getSession() == null) {
            throw new IllegalStateException("Geyser downstream session is not ready");
        }

        Channel channel = session.getDownstream().getSession().getChannel();
        ChannelPipeline pipeline = channel.pipeline();

        for (String name : pipeline.names()) {
            if (pipeline.get(name) instanceof NetworkSession) {
                if (pipeline.get("realcoords-java") != null) return;
                pipeline.addBefore(name, "realcoords-java", new JavaSessionHook(shift));
                shift.javaHookReady(pipeline.names().toString());
                channel.closeFuture().addListener(f -> shift.javaHookClosed());
                return;
            }
        }

        throw new IllegalStateException("Could not find MCProtocolLib NetworkSession handler; pipeline=" + pipeline.names());
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        Object transformed = msg;
        if (msg instanceof Packet) {
            try {
                transformed = shift.javaInbound(msg);
            } catch (Throwable t) {
                shift.fail("java-inbound", t);
            }
        }
        ctx.fireChannelRead(transformed);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        Object transformed = msg;
        if (msg instanceof Packet) {
            try {
                transformed = shift.javaOutbound(msg);
            } catch (Throwable t) {
                shift.fail("java-outbound", t);
            }
        }
        ctx.write(transformed, promise);
    }
}
