package dev.realcoords;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;

/**
 * LevelChunk 的資料尾端是一串網路格式 NBT（區塊內嵌的方塊實體，座標是絕對值）。
 * 找出尾端起點、平移座標後原樣接回。區塊的 chunkX/chunkZ 由呼叫端另外處理。
 */
final class ChunkRewriter {
    private ChunkRewriter() {}

    static byte[] rewrite(byte[] data, int offX, int offZ, ShiftConfig cfg, RealCoordsLog log) {
        if (offX == 0 && offZ == 0) return data;
        int tail = findTail(data);
        if (tail >= data.length) return data;   // 此區塊沒有方塊實體

        ByteBuf in = Unpooled.wrappedBuffer(data, tail, data.length - tail);
        ByteBuf out = Unpooled.buffer(data.length - tail + 64);
        try (NBTInputStream r = NbtUtils.createNetworkReader(new ByteBufInputStream(in));
             NBTOutputStream w = NbtUtils.createNetworkWriter(new ByteBufOutputStream(out))) {
            while (in.isReadable()) {
                Object t = r.readTag();
                w.writeTag(t instanceof NbtMap m ? NbtShift.shift(m, offX, offZ, cfg) : t);
            }
            byte[] res = new byte[tail + out.readableBytes()];
            System.arraycopy(data, 0, res, 0, tail);
            out.getBytes(out.readerIndex(), res, tail, out.readableBytes());
            return res;
        } catch (Exception e) {
            log.warning("[RealCoords] 區塊方塊實體改寫失敗，該區塊的方塊實體可能位置錯誤: " + e);
            return data;
        } finally {
            in.release();
            out.release();
        }
    }

    /** 回傳尾端 NBT 區段的起點；沒有則回傳 data.length */
    static int findTail(byte[] d) {
        for (int p = 0; p + 1 < d.length; p++) {
            if (d[p] == 0x0A && d[p + 1] == 0x00 && NbtScan.chainParsesToEnd(d, p)) return p;
        }
        return d.length;
    }
}
