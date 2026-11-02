package dev.realcoords;

import org.geysermc.geyser.api.extension.ExtensionLogger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 所有可調參數都在這裡，從 config.yml 讀入，沒有任何寫死的距離。 */
final class ShiftConfig {
    static final class Dim {
        boolean enabled = true;
        int trigger = 50000, reset = 20000, alignment = 256;
    }

    boolean enabled = true, debug;
    int cooldownMs = 5000, ackTimeoutMs = 3000, guardIntervalMs = 500;
    double ackTolerance = 4.0, maxJump = 64.0;

    boolean cacheEnabled = true, cacheCompress = true;
    int cacheMaxChunks = 2500, cacheMargin = 1500, resendPerTick = 24, evictMarginChunks = 4;

    final Map<Integer, Dim> dims = new HashMap<>();

    List<String> nbtX = List.of("x", "pistonPosX");
    List<String> nbtZ = List.of("z", "pistonPosZ");
    List<String> nbtTriples = List.of("AttachedBlocks", "BreakBlocks");

    boolean abEnabled = true, abHideVanilla = true, abMergeJava = true;
    int abIntervalMs = 500, abJavaKeepMs = 3000;
    String abFormat = "&fX &e{x}  &fY &e{y}  &fZ &e{z}  &7{facing}";
    String abNumber = "%,.1f";
    String abSep = " &8| &r";
    double eyeHeight = 1.62;
    String[] facing = {"S +Z", "W -X", "N -Z", "E +X"};

    Dim dim(int id) {
        Dim d = dims.get(id);
        return d != null ? d : dims.get(0);
    }

    // ---- 載入 ----

    static ShiftConfig load(Path dir, Class<?> anchor, ExtensionLogger log) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("config.yml");
        if (!Files.exists(file)) {
            try (InputStream in = anchor.getResourceAsStream("/config.yml")) {
                if (in != null) Files.copy(in, file);
            }
        }
        Map<String, Object> root;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object o = new Yaml().load(r);
            root = o instanceof Map<?, ?> ? cast(o) : new HashMap<>();
        }

        ShiftConfig c = new ShiftConfig();
        Map<String, Object> os = sec(root, "origin-shift");
        c.enabled = bool(os, "enabled", true);

        Dim base = new Dim();
        base.alignment = align(num(os, "alignment", 256));
        base.trigger = Math.max(1000, num(os, "trigger-distance", 50000));
        base.reset = num(os, "reset-distance", 20000);
        c.cooldownMs = num(os, "cooldown-ticks", 100) * 50;
        c.ackTimeoutMs = num(os, "ack-timeout-ticks", 60) * 50;
        c.ackTolerance = dbl(os, "ack-tolerance", 4.0);

        Map<String, Object> dsec = sec(os, "dimensions");
        String[] names = {"overworld", "the_nether", "the_end"};
        for (int id = 0; id < names.length; id++) {
            Map<String, Object> d = sec(dsec, names[id]);
            Dim x = new Dim();
            x.enabled = bool(d, "enabled", true);
            x.trigger = Math.max(1000, num(d, "trigger-distance", base.trigger));
            x.reset = num(d, "reset-distance", base.reset);
            x.alignment = align(num(d, "alignment", base.alignment));
            if (x.reset >= x.trigger) {
                log.warning("[RealCoords] " + names[id] + ": reset-distance 必須小於 trigger-distance，已自動調整");
                x.reset = x.trigger / 2;
            }
            c.dims.put(id, x);
        }

        Map<String, Object> sf = sec(root, "safety");
        c.maxJump = dbl(sf, "max-serverbound-jump", 64.0);
        c.guardIntervalMs = num(sf, "correction-interval-ticks", 10) * 50;
        c.debug = bool(sf, "debug-log", false);

        Map<String, Object> cc = sec(root, "chunk-cache");
        c.cacheEnabled = bool(cc, "enabled", true);
        c.cacheCompress = bool(cc, "compress", true);
        c.cacheMaxChunks = Math.max(64, num(cc, "max-chunks", 2500));
        c.cacheMargin = Math.max(256, num(cc, "activate-margin", 1500));
        c.resendPerTick = Math.max(1, num(cc, "resend-per-tick", 24));
        c.evictMarginChunks = Math.max(0, num(cc, "evict-margin-chunks", 4));

        Map<String, Object> nk = sec(root, "nbt-shift-keys");
        c.nbtX = strs(nk, "x", c.nbtX);
        c.nbtZ = strs(nk, "z", c.nbtZ);
        c.nbtTriples = strs(nk, "triples", c.nbtTriples);

        Map<String, Object> ab = sec(root, "actionbar");
        c.abEnabled = bool(ab, "enabled", true);
        c.abHideVanilla = bool(ab, "hide-vanilla-coordinates", true);
        c.abIntervalMs = Math.max(1, num(ab, "interval-ticks", 10)) * 50;
        c.abFormat = color(str(ab, "format", c.abFormat));
        c.abNumber = str(ab, "number-format", c.abNumber);
        c.abMergeJava = bool(ab, "merge-java-actionbar", true);
        c.abJavaKeepMs = num(ab, "java-actionbar-keep-ms", 3000);
        c.abSep = color(str(ab, "separator", c.abSep));
        c.eyeHeight = dbl(ab, "eye-height", 1.62);
        List<String> f = strs(ab, "facing", List.of(c.facing));
        if (f.size() == 4) c.facing = f.toArray(new String[0]);
        return c;
    }

    // ---- 小工具 ----

    private static int align(int a) {
        return Math.max(16, (a / 16) * 16);
    }

    private static String color(String s) {
        return s.replace('&', '\u00a7');
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }

    private static Map<String, Object> sec(Map<String, Object> m, String k) {
        Object o = m.get(k);
        return o instanceof Map<?, ?> ? cast(o) : new HashMap<>();
    }

    private static int num(Map<String, Object> m, String k, int d) {
        return m.get(k) instanceof Number n ? n.intValue() : d;
    }

    private static double dbl(Map<String, Object> m, String k, double d) {
        return m.get(k) instanceof Number n ? n.doubleValue() : d;
    }

    private static boolean bool(Map<String, Object> m, String k, boolean d) {
        return m.get(k) instanceof Boolean b ? b : d;
    }

    private static String str(Map<String, Object> m, String k, String d) {
        return m.get(k) instanceof String s ? s : d;
    }

    private static List<String> strs(Map<String, Object> m, String k, List<String> d) {
        if (!(m.get(k) instanceof List<?> l)) return d;
        List<String> out = new ArrayList<>();
        for (Object o : l) out.add(String.valueOf(o));
        return out;
    }
}
