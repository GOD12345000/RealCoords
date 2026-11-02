package dev.realcoords;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import org.geysermc.geyser.api.extension.ExtensionLogger;
import org.geysermc.mcprotocollib.network.NetworkConstants;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.ConnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Installs a passive packet observation bridge before MCProtocolLib's manager.
 *
 * <p>This stage deliberately does not alter packets or coordinates. Its only
 * purpose is to prove that RealCoords sees both directions at the correct
 * point in the pipeline before any coordinate transformation is introduced.</p>
 */
public final class DownstreamSessionHook extends SessionAdapter {
    private static final String HANDLER_NAME = "realcoords-downstream-packet-bridge";

    private final ExtensionLogger logger;

    public DownstreamSessionHook(ExtensionLogger logger) {
        this.logger = logger;
    }

    @Override
    public void connected(ConnectedEvent event) {
        Session session = event.getSession();

        try {
            Channel channel = session.getChannel();
            if (!channel.isActive()) {
                throw new IllegalStateException("MCProtocolLib channel is not active during ConnectedEvent");
            }
            if (!channel.eventLoop().inEventLoop()) {
                throw new IllegalStateException("ConnectedEvent was not delivered on the channel event loop");
            }

            ChannelPipeline pipeline = channel.pipeline();
            ChannelHandler codec = pipeline.get(NetworkConstants.CODEC_NAME);
            ChannelHandler manager = pipeline.get(NetworkConstants.MANAGER_NAME);

            if (codec == null) {
                throw new IllegalStateException("Expected MCProtocolLib packet codec was not found");
            }
            if (!(session instanceof ChannelHandler managerHandler) || manager != managerHandler) {
                throw new IllegalStateException("Expected MCProtocolLib manager handler was not found or did not match the session");
            }

            ChannelHandler existing = pipeline.get(HANDLER_NAME);
            if (existing != null) {
                if (existing instanceof PacketBridgeHandler) {
                    logger.debug("[RealCoords] Packet bridge was already installed for this downstream session.");
                    return;
                }
                throw new IllegalStateException("A different handler already uses " + HANDLER_NAME);
            }

            pipeline.addBefore(NetworkConstants.MANAGER_NAME, HANDLER_NAME, new PacketBridgeHandler(logger));
            if (!(pipeline.get(HANDLER_NAME) instanceof PacketBridgeHandler)) {
                throw new IllegalStateException("Netty did not retain the RealCoords packet bridge");
            }

            logger.info("[RealCoords] Passive downstream packet bridge installed before MCProtocolLib manager ("
                    + session.getClass().getSimpleName() + "). No coordinates are being changed.");
        } catch (Throwable error) {
            logger.severe("[RealCoords] Failed to install the downstream packet bridge; closing this Java session.", error);
            session.disconnect("RealCoords could not safely initialize the downstream packet hook.", error);
        }
    }

    private static final class PacketBridgeHandler extends ChannelDuplexHandler {
        private final ExtensionLogger logger;
        private final AtomicBoolean firstInbound = new AtomicBoolean();
        private final AtomicBoolean firstOutbound = new AtomicBoolean();

        private PacketBridgeHandler(ExtensionLogger logger) {
            this.logger = logger;
        }

        @Override
        public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
            if (message instanceof Packet packet && firstInbound.compareAndSet(false, true)) {
                logger.debug("[RealCoords] First inbound Java packet intercepted: " + packet.getClass().getName());
            }
            super.channelRead(context, message);
        }

        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
            if (message instanceof Packet packet && firstOutbound.compareAndSet(false, true)) {
                logger.debug("[RealCoords] First outbound Java packet intercepted: " + packet.getClass().getName());
            }
            super.write(context, message, promise);
        }
    }
}
