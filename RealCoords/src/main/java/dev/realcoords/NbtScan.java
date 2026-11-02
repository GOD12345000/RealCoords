package dev.realcoords;

/**
 * 不配置物件的「網路格式 NBT」結構掃描器，只用來判斷一段位元組是否剛好是
 * 一串完整的根 Compound（用來在區塊資料尾端找出方塊實體區段的起點）。
 */
final class NbtScan {
    private NbtScan() {}

    /** 從 start 開始是否為「連續的根 Compound，且剛好吃到陣列結尾」 */
    static boolean chainParsesToEnd(byte[] d, int start) {
        int[] p = {start};
        try {
            while (p[0] < d.length) {
                if (d[p[0]++] != 10) return false;   // 根標籤必須是 Compound
                skipString(d, p);                    // 根名稱
                skipPayload(d, p, 10, 0);
            }
            return p[0] == d.length;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int uvar(byte[] d, int[] p) {
        int v = 0, s = 0;
        while (true) {
            int b = d[p[0]++] & 0xFF;
            v |= (b & 0x7F) << s;
            if ((b & 0x80) == 0) return v;
            s += 7;
            if (s > 35) throw new IllegalStateException("varint too long");
        }
    }

    private static int ivar(byte[] d, int[] p) {
        int v = uvar(d, p);
        return (v >>> 1) ^ -(v & 1);
    }

    private static void skipVarLong(byte[] d, int[] p) {
        for (int i = 0; i < 10; i++) {
            if ((d[p[0]++] & 0x80) == 0) return;
        }
        throw new IllegalStateException("varlong too long");
    }

    private static void need(byte[] d, int[] p, long n) {
        if (n < 0 || p[0] + n > d.length) throw new IllegalStateException("out of range");
    }

    private static void skipString(byte[] d, int[] p) {
        int len = uvar(d, p);
        need(d, p, len);
        p[0] += len;
    }

    private static void skipPayload(byte[] d, int[] p, int type, int depth) {
        if (depth > 512) throw new IllegalStateException("too deep");
        switch (type) {
            case 1 -> { need(d, p, 1); p[0] += 1; }
            case 2 -> { need(d, p, 2); p[0] += 2; }
            case 3 -> ivar(d, p);
            case 4 -> skipVarLong(d, p);
            case 5 -> { need(d, p, 4); p[0] += 4; }
            case 6 -> { need(d, p, 8); p[0] += 8; }
            case 7 -> { int n = ivar(d, p); need(d, p, n); p[0] += n; }
            case 8 -> skipString(d, p);
            case 9 -> {
                need(d, p, 1);
                int et = d[p[0]++];
                int n = ivar(d, p);
                if (n < 0 || (et == 0 && n > 0)) throw new IllegalStateException("bad list");
                if (n > d.length - p[0]) throw new IllegalStateException("list too long");
                for (int i = 0; i < n; i++) skipPayload(d, p, et, depth + 1);
            }
            case 10 -> {
                while (true) {
                    need(d, p, 1);
                    int t = d[p[0]++];
                    if (t == 0) break;
                    skipString(d, p);
                    skipPayload(d, p, t, depth + 1);
                }
            }
            case 11 -> { int n = ivar(d, p); if (n < 0 || n > d.length - p[0]) throw new IllegalStateException(); for (int i = 0; i < n; i++) ivar(d, p); }
            case 12 -> { int n = ivar(d, p); if (n < 0 || n > d.length - p[0]) throw new IllegalStateException(); for (int i = 0; i < n; i++) skipVarLong(d, p); }
            default -> throw new IllegalStateException("bad tag type " + type);
        }
    }
}
