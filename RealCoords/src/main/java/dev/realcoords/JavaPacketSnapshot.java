package dev.realcoords;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacket;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

/** Lossless packet snapshot using the packet's own MCProtocolLib codec. */
final class JavaPacketSnapshot {
    private JavaPacketSnapshot() { }

    static byte[] serialize(MinecraftPacket packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            packet.serialize(buffer);
            byte[] result = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), result);
            return result;
        } finally {
            buffer.release();
        }
    }

    static <T extends MinecraftPacket> T deserialize(Class<T> packetType, byte[] payload) {
        ByteBuf buffer = Unpooled.wrappedBuffer(payload);
        try {
            Constructor<T> constructor = packetType.getConstructor(ByteBuf.class);
            return constructor.newInstance(buffer);
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException
                 | InvocationTargetException exception) {
            throw new IllegalStateException("Unable to restore MCProtocolLib packet "
                    + packetType.getName(), exception);
        } finally {
            buffer.release();
        }
    }
}
