package dev.realcoords;

import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;

import java.util.ArrayList;
import java.util.List;

/** 平移方塊實體 NBT 裡的絕對座標。要平移哪些鍵由 config.yml 決定。 */
final class NbtShift {
    private NbtShift() {}

    /** 新值 = 舊值 - dx / dz（送往客戶端用正的偏移；客戶端送回來時傳負的） */
    @SuppressWarnings("unchecked")
    static NbtMap shift(NbtMap m, int dx, int dz, ShiftConfig cfg) {
        if (m == null || (dx == 0 && dz == 0)) return m;
        NbtMapBuilder b = null;
        for (String k : cfg.nbtX) {
            if (m.get(k) instanceof Integer i) {
                if (b == null) b = m.toBuilder();
                b.putInt(k, i - dx);
            }
        }
        for (String k : cfg.nbtZ) {
            if (m.get(k) instanceof Integer i) {
                if (b == null) b = m.toBuilder();
                b.putInt(k, i - dz);
            }
        }
        for (String k : cfg.nbtTriples) {
            if (m.get(k) instanceof NbtList<?> l && l.getType() == NbtType.INT) {
                List<Integer> out = new ArrayList<>((List<Integer>) l);
                for (int i = 0; i + 2 < out.size(); i += 3) {
                    out.set(i, out.get(i) - dx);
                    out.set(i + 2, out.get(i + 2) - dz);
                }
                if (b == null) b = m.toBuilder();
                b.putList(k, NbtType.INT, out);
            }
        }
        return b == null ? m : b.build();
    }
}
