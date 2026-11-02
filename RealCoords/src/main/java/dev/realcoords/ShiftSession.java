package dev.realcoords;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.GameRuleData;
import org.cloudburstmc.protocol.bedrock.data.PlayerBlockActionData;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.geysermc.geyser.session.GeyserSession;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * 單一基岩版連線的「浮動原點」轉碼器。
 *
 * 約定：Geyser 內部與 Java 伺服器永遠使用【真實座標】；只有在 Bedrock 封包進出
 * 網路管線的那一刻，才會做 bedrock = real - offset / real = bedrock + offset。
 * 所有狀態只在該連線的 Netty event loop 上存取，不需要鎖。
 */
final class ShiftSession {
    private static final EnumSet<UpdateBlockPacket.Flag> UPD_FLAGS =
            EnumSet.of(UpdateBlockPacket.Flag.NEIGHBORS, UpdateBlockPacket.Flag.NETWORK);

    record PosLayer(int x, int y, int z, int layer) {}

    record BlockUpd(Vector3i pos, BlockDefinition def, int layer) {}

    static final class Ent {
        float x, y, z, pitch, yaw, headYaw;
        boolean ground;
    }

    static final class ChunkEntry {
        final int cx, cz, subLen, dim, subLimit, rawLen;
        final byte[] data;
        final boolean zipped;
        final Map<PosLayer, BlockUpd> blocks = new HashMap<>();
        final Map<Vector3i, NbtMap> tiles = new HashMap<>();

        ChunkEntry(int cx, int cz, int subLen, int dim, int subLimit, int rawLen, byte[] data, boolean zipped) {
            this.cx = cx; this.cz = cz; this.subLen = subLen; this.dim = dim;
            this.subLimit = subLimit; this.rawLen = rawLen; this.data = data; this.zipped = zipped;
        }
    }

    private final GeyserSession gs;
    private final Channel ch;
    private final ShiftConfig cfg;
    private final RealCoordsLog log;

    // 目前套用的偏移（方塊單位，必為 16 的倍數）
    private volatile int offX, offZ;
    private int dim;
    private long localId = Long.MIN_VALUE;

    // 玩家最後已知的【真實】位置與朝向
    private boolean havePos;
    private double lx, ly, lz;
    private float pitch, yaw, headYaw;

    // 等待客戶端到位（伺服器傳送／換原點之後）
    private boolean hold;
    private long holdDeadline, lastShiftMs, lastGuardMs;
    private int shiftGen;

    // 區塊快取（真實座標版）與實體位置
    private final Map<Long, ChunkEntry> chunks = new HashMap<>();
    private double pubX, pubZ;
    private int pubRadius;
    private final Map<Long, Ent> entities = new HashMap<>();

    // 自己送出的封包（已是基岩座標，不可再轉換）
    private final Set<BedrockPacket> own = Collections.newSetFromMap(new IdentityHashMap<>());

    private String javaBar;
    private long javaBarMs;
    private boolean hookIn;
    private final Set<String> warned = new HashSet<>();
    private ScheduledFuture<?> barTask;

    ShiftSession(GeyserSession gs, Channel ch, ShiftConfig cfg, RealCoordsLog log) {
        this.gs = gs;
        this.ch = ch;
        this.cfg = cfg;
        this.log = log;
    }

    void start() {
        if (cfg.abEnabled) {
            barTask = ch.eventLoop().scheduleAtFixedRate(() -> {
                try {
                    tickBar();
                } catch (Throwable t) {
                    fail("actionbar", t);
                }
            }, 2000, cfg.abIntervalMs, TimeUnit.MILLISECONDS);
        }
    }

    void close() {
        if (barTask != null) barTask.cancel(false);
        chunks.clear();
        entities.clear();
        own.clear();
    }

    void fail(String where, Throwable t) {
        warnOnce("fail-" + where, where + " 處理時發生例外（已略過該封包）: " + t);
        if (cfg.debug) t.printStackTrace();
    }

    // =====================================================================
    // 座標轉換
    // =====================================================================

    private CoordinateTransform coordinates() {
        return new CoordinateTransform(offX, offZ);
    }

    private Vector3f toB(Vector3f v) {
        CoordinateTransform t = coordinates();
        return v == null ? null : Vector3f.from(t.toBedrockX(v.getX()), v.getY(), t.toBedrockZ(v.getZ()));
    }

    private Vector3f toR(Vector3f v) {
        CoordinateTransform t = coordinates();
        return v == null ? null : Vector3f.from(t.toJavaX(v.getX()), v.getY(), t.toJavaZ(v.getZ()));
    }

    private Vector3i toB(Vector3i v) {
        CoordinateTransform t = coordinates();
        return v == null ? null : Vector3i.from(t.toBedrockX(v.getX()), v.getY(), t.toBedrockZ(v.getZ()));
    }

    private Vector3i toR(Vector3i v) {
        CoordinateTransform t = coordinates();
        return v == null ? null : Vector3i.from(t.toJavaX(v.getX()), v.getY(), t.toJavaZ(v.getZ()));
    }

    private ShiftConfig.Dim dimCfg() {
        return cfg.dim(dim);
    }

    private int nextOff(int cur, double real, ShiftConfig.Dim d) {
        if (!d.enabled) return 0;
        if (Math.abs(real - cur) > d.trigger) return (int) (Math.round(real / d.alignment) * d.alignment);
        if (cur != 0 && Math.abs(real) < d.reset) return 0;
        return cur;
    }

    // =====================================================================
    // 往客戶端的封包（Geyser → Bedrock）
    // =====================================================================

    void outbound(BedrockPacketWrapper w) {
        BedrockPacket p = w.getPacket();
        if (p == null || own.remove(p)) return;

        if (p instanceof LevelChunkPacket c) {
            chunk(c);
        } else if (p instanceof UpdateBlockPacket u) {
            recordBlock(u.getBlockPosition(), u.getDefinition(), u.getDataLayer());
            u.setBlockPosition(toB(u.getBlockPosition()));
        } else if (p instanceof UpdateBlockSyncedPacket u) {
            recordBlock(u.getBlockPosition(), u.getDefinition(), u.getDataLayer());
            u.setBlockPosition(toB(u.getBlockPosition()));
        } else if (p instanceof BlockEntityDataPacket be) {
            recordTile(be.getBlockPosition(), be.getData());
            be.setBlockPosition(toB(be.getBlockPosition()));
            be.setData(NbtShift.shift(be.getData(), offX, offZ, cfg));
        } else if (p instanceof BlockEventPacket be) {
            be.setBlockPosition(toB(be.getBlockPosition()));
        } else if (p instanceof MovePlayerPacket m) {
            Vector3f position = m.getPosition();
            if (m.getRuntimeEntityId() == localId) {
                // Java inbound should already have localized this position. Recover safely if
                // a respawn/backend transition lets one unshifted absolute position through.
                ShiftConfig.Dim d = dimCfg();
                boolean looksAbsolute = Math.abs(position.getX()) > d.trigger
                        || Math.abs(position.getZ()) > d.trigger;
                if (looksAbsolute && (offX == 0 && offZ == 0)) {
                    int nx = nextOff(0, position.getX(), d);
                    int nz = nextOff(0, position.getZ(), d);
                    if (nx != 0 || nz != 0) {
                        offX = nx;
                        offZ = nz;
                        position = toB(position);
                        m.setPosition(position);
                        log.warning(String.format(
                                "[RealCoords] %s: fallback localized an unshifted player position; origin=(%d,%d)",
                                name(), offX, offZ));
                    }
                } else if (looksAbsolute && (offX != 0 || offZ != 0)) {
                    // Critical anti-accumulation guard: never interpret a raw world position
                    // as local and add the existing offset to it a second time.
                    position = toB(position);
                    m.setPosition(position);
                    warnRate("unshifted-player-position",
                            "[RealCoords] " + name() + ": received an unshifted absolute player position; applied current origin once",
                            5000);
                }
                onLocalPosition(position, m.getRotation());
            } else {
                // Entity absolute positions are localized in javaInbound while still double precision.
                trackEntity(m.getRuntimeEntityId(), toR(position), m.getRotation(), m.isOnGround());
            }
        } else if (p instanceof MoveEntityAbsolutePacket m) {
            // Do not subtract the origin here: Java absolute positions were shifted before
            // Geyser converted them to Bedrock float coordinates.
            trackEntity(m.getRuntimeEntityId(), toR(m.getPosition()), m.getRotation(), m.isOnGround());
        } else if (p instanceof MoveEntityDeltaPacket d) {
            // Relative deltas are vectors, not world positions. Never shift them by the origin.
            Ent e = entities.get(d.getRuntimeEntityId());
            Set<MoveEntityDeltaPacket.Flag> flags = d.getFlags();
            if (e != null) {
                if (flags.contains(MoveEntityDeltaPacket.Flag.HAS_X)) e.x += d.getX();
                if (flags.contains(MoveEntityDeltaPacket.Flag.HAS_Y)) e.y += d.getY();
                if (flags.contains(MoveEntityDeltaPacket.Flag.HAS_Z)) e.z += d.getZ();
            }
        } else if (p instanceof AddEntityPacket a) {
            trackEntity(a.getRuntimeEntityId(), toR(a.getPosition()), null, false);
            metaShift(a.getMetadata());
        } else if (p instanceof AddPlayerPacket a) {
            trackEntity(a.getRuntimeEntityId(), toR(a.getPosition()), null, false);
            metaShift(a.getMetadata());
        } else if (p instanceof AddItemEntityPacket a) {
            trackEntity(a.getRuntimeEntityId(), toR(a.getPosition()), null, false);
            metaShift(a.getMetadata());
        } else if (p instanceof AddHangingEntityPacket a) {
            // Hanging entity spawn coordinates originate from the localized Java spawn packet.
            trackEntity(a.getRuntimeEntityId(), toR(a.getPosition()), null, false);
        } else if (p instanceof UpdateSubChunkBlocksPacket u) {
            recordSubChunkBlocks(u);
            BedrockPacketCoordinateTransformer.transformOutbound(u, coordinates());
        } else if (p instanceof LevelEventGenericPacket event) {
            if (!BedrockPacketCoordinateTransformer.transformOutbound(event, coordinates())) {
                warnOnce("generic-event-" + event.getType(),
                        "未識別的 LevelEventGenericPacket 座標結構: " + event.getType());
            }
        } else if (BedrockPacketCoordinateTransformer.transformOutbound(p, coordinates())) {
            // Extended packet coordinate schemas are handled by the dedicated transformer.
        } else if (p instanceof RemoveEntityPacket r) {
            entities.remove(r.getUniqueEntityId());
        } else if (p instanceof SetEntityDataPacket s) {
            metaShift(s.getMetadata());
        } else if (p instanceof NetworkChunkPublisherUpdatePacket n) {
            pubX = n.getPosition().getX();
            pubZ = n.getPosition().getZ();
            pubRadius = n.getRadius();
            evict();
            n.setPosition(toB(n.getPosition()));
        } else if (p instanceof SetSpawnPositionPacket s) {
            s.setBlockPosition(toB(s.getBlockPosition()));
            s.setSpawnPosition(toB(s.getSpawnPosition()));
        } else if (p instanceof LevelEventPacket l) {
            l.setPosition(toB(l.getPosition()));
        } else if (p instanceof LevelSoundEventPacket l) {
            l.setPosition(toB(l.getPosition()));
        } else if (p instanceof SpawnParticleEffectPacket s) {
            s.setPosition(toB(s.getPosition()));
        } else if (p instanceof PlaySoundPacket s) {
            s.setPosition(toB(s.getPosition()));
        } else if (p instanceof ContainerOpenPacket c) {
            c.setBlockPosition(toB(c.getBlockPosition()));
        } else if (p instanceof CorrectPlayerMovePredictionPacket c) {
            c.setPosition(toB(c.getPosition()));
        } else if (p instanceof RespawnPacket r) {
            if (r.getPosition() != null) onLocalPosition(r.getPosition(), null);
            r.setPosition(toB(r.getPosition()));
        } else if (p instanceof ChangeDimensionPacket c) {
            setDim(c.getDimension());
            if (c.getPosition() != null) onLocalPosition(c.getPosition(), null);
            c.setPosition(toB(c.getPosition()));
        } else if (p instanceof StartGamePacket s) {
            localId = s.getRuntimeEntityId();
            setDim(s.getDimensionId());
            hideCoords(s.getGamerules());
            s.setPlayerPosition(toB(s.getPlayerPosition()));
            s.setDefaultSpawn(toB(s.getDefaultSpawn()));
        } else if (p instanceof GameRulesChangedPacket g) {
            hideCoords(g.getGameRules());
        } else if (p instanceof SetTitlePacket t) {
            if (cfg.abEnabled && cfg.abMergeJava && t.getType() == SetTitlePacket.Type.ACTIONBAR) {
                javaBar = t.getText();
                javaBarMs = System.currentTimeMillis();
                if (havePos) t.setText(barText(javaBarMs));
            }
        }
    }

    private void setDim(int d) {
        if (d == dim && !chunks.isEmpty()) return;
        dim = d;
        offX = 0;
        offZ = 0;
        chunks.clear();
        entities.clear();
        havePos = false;
        lx = ly = lz = 0;
        hold = false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void hideCoords(List rules) {
        if (!cfg.abHideVanilla || rules == null) return;
        rules.removeIf(o -> o instanceof GameRuleData<?> g && "showcoordinates".equalsIgnoreCase(g.getName()));
        rules.add(new GameRuleData<>("showcoordinates", false));
    }

    private void metaShift(EntityDataMap md) {
        if (md == null || (offX == 0 && offZ == 0)) return;
        Vector3i bed = md.get(EntityDataTypes.BED_POSITION);
        if (bed != null) md.put(EntityDataTypes.BED_POSITION, toB(bed));
        Vector3i tgt = md.get(EntityDataTypes.BLOCK_TARGET_POS);
        if (tgt != null) md.put(EntityDataTypes.BLOCK_TARGET_POS, toB(tgt));
    }

    private void trackEntity(long id, Vector3f real, Vector3f rot, boolean ground) {
        if (id == localId || real == null) return;
        Ent e = entities.computeIfAbsent(id, k -> new Ent());
        e.x = real.getX(); e.y = real.getY(); e.z = real.getZ();
        if (rot != null) { e.pitch = rot.getX(); e.yaw = rot.getY(); e.headYaw = rot.getZ(); }
        e.ground = ground;
    }

    // =====================================================================
    // 伺服器傳送玩家 → 更新真實位置、必要時換原點
    // =====================================================================

    private void onLocalPosition(Vector3f local, Vector3f rot) {
        // Geyser 收到的玩家位置已被 Java inbound hook 還原成 local-space。
        lx = local.getX() + offX;
        ly = local.getY() - cfg.eyeHeight;
        lz = local.getZ() + offZ;
        if (rot != null) { pitch = rot.getX(); yaw = rot.getY(); headYaw = rot.getZ(); }
        havePos = true;
        if (offX == 0 && offZ == 0) {
            ShiftConfig.Dim d = dimCfg();
            int nx = nextOff(offX, lx, d), nz = nextOff(offZ, lz, d);
            if (nx != offX || nz != offZ) {
                // 初次進入遠距離世界：下游位置封包已先轉成 local-space，直接建立原點。
                offX = nx;
                offZ = nz;
                lastShiftMs = System.currentTimeMillis();
                log.info(String.format("[RealCoords] %s: initial origin -> (%d,%d), real=(%.3f, %.3f)",
                        name(), offX, offZ, lx, lz));
            }
        }
        hold = true;
        holdDeadline = System.currentTimeMillis() + cfg.ackTimeoutMs;
        ShiftConfig.Dim d = dimCfg();
        int nx = nextOff(offX, lx, d), nz = nextOff(offZ, lz, d);
        if (nx != offX || nz != offZ) applyShift(nx, nz, false);
    }

    // =====================================================================
    // 來自客戶端的封包（Bedrock → Geyser）
    // =====================================================================

    void inbound(BedrockPacketWrapper w) {
        BedrockPacket p = w.getPacket();
        if (p == null) return;

        if (p instanceof PlayerAuthInputPacket a) {
            onAuthInput(a);
        } else if (p instanceof MovePlayerPacket) {
            // 玩家座標維持 Bedrock local-space；Java movement 封包在下游管線才加回原點。
        } else if (p instanceof MoveEntityAbsolutePacket m) {
            m.setPosition(toR(m.getPosition()));
        } else if (p instanceof InventoryTransactionPacket) {
            // Geyser 必須先用 local-space 做距離與方塊互動驗證。
        } else if (p instanceof PlayerActionPacket) {
            // 方塊動作座標留在 local-space，由 Java 下游封包 hook 加回原點。
        } else if (p instanceof BlockPickRequestPacket) {
            // 保留 local-space。
        } else if (p instanceof LecternUpdatePacket) {
            // 保留 local-space。
        } else if (p instanceof CommandBlockUpdatePacket) {
            // 保留 local-space。
        } else if (p instanceof ItemFrameDropItemPacket) {
            // 保留 local-space。
        } else if (p instanceof BlockEntityDataPacket be) {
            // NBT 內容可能包含世界座標，但封包位置本身供 Geyser 驗證時必須保持 local-space。
            be.setData(NbtShift.shift(be.getData(), -offX, -offZ, cfg));
        }
    }

    private void onAuthInput(PlayerAuthInputPacket a) {
        Vector3f pos = a.getPosition();
        if (pos == null) return;
        if (!hookIn) {
            hookIn = true;
            log.info("[RealCoords] hook active: " + name());
        }
        long now = System.currentTimeMillis();
        double rx = pos.getX() + offX, rz = pos.getZ() + offZ, ry = pos.getY() - cfg.eyeHeight;
        debugRate("bedrock-input", String.format(
                "[RealCoords][MOVE IN] %s local=(%.4f, %.4f, %.4f) offset=(%d,%d) real=(%.4f, %.4f, %.4f)",
                name(), pos.getX(), pos.getY(), pos.getZ(), offX, offZ, rx, pos.getY(), rz), 1000);

        // Geyser 的碰撞、互動距離與方塊預測必須使用小範圍 local-space。
        // 真實座標只在 Java 下游封包層以 double 加回偏移。
        double localX = pos.getX(), localY = pos.getY(), localZ = pos.getZ();
        boolean accept = true;

        if (hold) {
            double ex = lx - offX, ez = lz - offZ;   // 客戶端「應該」在的基岩座標
            if (Math.hypot(pos.getX() - ex, pos.getZ() - ez) <= cfg.ackTolerance || now > holdDeadline) {
                hold = false;                         // 已到位（或逾時）
            } else {
                localX = lx - offX; localY = ly + cfg.eyeHeight; localZ = lz - offZ; // 舊座標系：對 Geyser 隱藏
                accept = false;
            }
        }

        if (accept && havePos && Math.hypot(rx - lx, rz - lz) > cfg.maxJump) {
            // 防呆閘：不合理的瞬間位移，絕不轉交給 Java 伺服器
            localX = lx - offX; localZ = lz - offZ;
            accept = false;
            warnRate("guard", String.format(
                    "%s 回報了不合理的座標跳躍 (%.1f,%.1f) -> (%.1f,%.1f)，已丟棄並要求客戶端回到 (%.1f,%.1f)",
                    name(), lx, lz, rx, rz, lx, lz), 5000);
            if (now - lastGuardMs >= cfg.guardIntervalMs) {
                lastGuardMs = now;
                send(teleportPacket());
                hold = true;
                holdDeadline = now + cfg.ackTimeoutMs;
            }
        }

        if (!accept) {
            a.setPosition(Vector3f.from((float) localX, (float) localY, (float) localZ));
        }
        if (accept) {
            lx = rx; ly = ry; lz = rz;
            havePos = true;
            Vector3f rot = a.getRotation();
            if (rot != null) { pitch = rot.getX(); yaw = rot.getY(); headYaw = rot.getZ(); }
        }

        // PlayerAuthInput 的方塊動作與 ItemUseTransaction 也維持 local-space；
        // Geyser 完成碰撞／放置判斷並產生 Java 封包後，再由下游 hook 加回原點。

        // 是否需要換原點（放在最後，才不影響上面的轉換）
        if (accept && now - lastShiftMs >= cfg.cooldownMs) {
            ShiftConfig.Dim d = dimCfg();
            int nx = nextOff(offX, lx, d), nz = nextOff(offZ, lz, d);
            if (nx != offX || nz != offZ) {
                // 延到目前 Bedrock 封包完成 Geyser 翻譯後再換原點，
                // 避免同一個 movement 封包被新 offset 二次加偏移。
                ch.eventLoop().execute(() -> {
                    applyShift(nx, nz, true);
                    hold = true;
                    holdDeadline = System.currentTimeMillis() + cfg.ackTimeoutMs;
                });
            }
        }
    }

    // =====================================================================
    // 換原點
    // =====================================================================

    private void applyShift(int nx, int nz, boolean teleport) {
        log.info(String.format("[RealCoords] %s: 原點 (%d,%d) -> (%d,%d)，真實座標 (%.1f, %.1f)",
                name(), offX, offZ, nx, nz, lx, lz));
        offX = nx;
        offZ = nz;
        lastShiftMs = System.currentTimeMillis();
        int gen = ++shiftGen;
        // 等目前這個封包處理完再送，確保順序正確
        ch.eventLoop().execute(() -> afterShift(teleport, gen));
    }

    private void afterShift(boolean teleport, int gen) {
        if (gen != shiftGen) return;   // 已被更新的換原點取代
        if (teleport) send(teleportPacket());
        if (pubRadius > 0) {
            NetworkChunkPublisherUpdatePacket n = new NetworkChunkPublisherUpdatePacket();
            n.setPosition(Vector3i.from((int) Math.floor(lx - offX), (int) ly, (int) Math.floor(lz - offZ)));
            n.setRadius(pubRadius);
            send(n);
        }
        resendEntities();
        if (cfg.cacheEnabled) {
            List<ChunkEntry> list = new ArrayList<>();
            double lim = (pubRadius > 0 ? pubRadius : 256) + 32.0;
            for (ChunkEntry e : chunks.values()) {
                if (Math.hypot(e.cx * 16 + 8 - lx, e.cz * 16 + 8 - lz) <= lim) list.add(e);
            }
            list.sort(Comparator.comparingDouble(e -> Math.hypot(e.cx * 16 + 8 - lx, e.cz * 16 + 8 - lz)));
            resendBatch(list, 0, gen);
        }
    }

    private MovePlayerPacket teleportPacket() {
        MovePlayerPacket t = new MovePlayerPacket();
        t.setRuntimeEntityId(localId);
        t.setPosition(Vector3f.from((float) (lx - offX), (float) (ly + cfg.eyeHeight), (float) (lz - offZ)));
        t.setRotation(Vector3f.from(pitch, yaw, headYaw));
        t.setMode(MovePlayerPacket.Mode.TELEPORT);
        t.setOnGround(true);
        t.setRidingRuntimeEntityId(0);
        t.setTeleportationCause(MovePlayerPacket.TeleportationCause.BEHAVIOR);
        t.setTick(0);
        return t;
    }

    private void resendEntities() {
        double lim = (pubRadius > 0 ? pubRadius : 256) + 64.0;
        for (Map.Entry<Long, Ent> en : entities.entrySet()) {
            Ent e = en.getValue();
            if (Math.hypot(e.x - lx, e.z - lz) > lim) continue;
            MoveEntityAbsolutePacket m = new MoveEntityAbsolutePacket();
            m.setRuntimeEntityId(en.getKey());
            m.setPosition(Vector3f.from((float) (e.x - offX), e.y, (float) (e.z - offZ)));
            m.setRotation(Vector3f.from(e.pitch, e.yaw, e.headYaw));
            m.setOnGround(e.ground);
            m.setTeleported(true);
            send(m);
        }
    }

    private void resendBatch(List<ChunkEntry> list, int from, int gen) {
        if (gen != shiftGen || !ch.isActive()) return;
        int to = Math.min(from + cfg.resendPerTick, list.size());
        for (int i = from; i < to; i++) sendChunk(list.get(i));
        if (to < list.size()) {
            ch.eventLoop().schedule(() -> resendBatch(list, to, gen), 50, TimeUnit.MILLISECONDS);
        }
    }

    private void sendChunk(ChunkEntry e) {
        byte[] raw = e.zipped ? inflate(e.data, e.rawLen) : e.data;
        if (raw == null) return;
        LevelChunkPacket pk = new LevelChunkPacket();
        pk.setChunkX(e.cx - offX / 16);
        pk.setChunkZ(e.cz - offZ / 16);
        pk.setSubChunksLength(e.subLen);
        pk.setDimension(e.dim);
        pk.setSubChunkLimit(e.subLimit);
        pk.setCachingEnabled(false);
        pk.setRequestSubChunks(false);
        pk.setData(Unpooled.wrappedBuffer(ChunkRewriter.rewrite(raw, offX, offZ, cfg, log)));
        send(pk);
        // 補回區塊送出之後發生的方塊／方塊實體變更
        for (BlockUpd u : e.blocks.values()) {
            UpdateBlockPacket up = new UpdateBlockPacket();
            up.setBlockPosition(toB(u.pos()));
            up.setDefinition(u.def());
            up.setDataLayer(u.layer());
            up.getFlags().addAll(UPD_FLAGS);
            send(up);
        }
        for (Map.Entry<Vector3i, NbtMap> t : e.tiles.entrySet()) {
            BlockEntityDataPacket bp = new BlockEntityDataPacket();
            bp.setBlockPosition(toB(t.getKey()));
            bp.setData(NbtShift.shift(t.getValue(), offX, offZ, cfg));
            send(bp);
        }
    }

    // =====================================================================
    // 區塊快取
    // =====================================================================

    private boolean cacheActive() {
        if (!cfg.cacheEnabled || !havePos) return false;
        ShiftConfig.Dim d = dimCfg();
        if (!d.enabled) return false;
        if (offX != 0 || offZ != 0) return true;
        double lim = d.trigger - cfg.cacheMargin;
        return Math.abs(lx) > lim || Math.abs(lz) > lim;
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private void chunk(LevelChunkPacket c) {
        int rcx = c.getChunkX(), rcz = c.getChunkZ();
        if (c.isRequestSubChunks() || c.isCachingEnabled()) {
            warnOnce("chunkmode", "LevelChunk 使用子區塊請求／快取模式，無法改寫，區塊可能錯位");
            c.setChunkX(rcx - offX / 16);
            c.setChunkZ(rcz - offZ / 16);
            return;
        }
        ByteBuf old = c.getData();
        byte[] raw = ByteBufUtil.getBytes(old);
        if (cacheActive()) {
            chunks.put(key(rcx, rcz), makeEntry(c, raw));
            if (chunks.size() > cfg.cacheMaxChunks) evict();
        } else if (!chunks.isEmpty()) {
            chunks.clear();
        }
        if (offX != 0 || offZ != 0) {
            byte[] shifted = ChunkRewriter.rewrite(raw, offX, offZ, cfg, log);
            if (shifted != raw) {
                c.setData(Unpooled.wrappedBuffer(shifted));
                ReferenceCountUtil.safeRelease(old);
            }
        }
        c.setChunkX(rcx - offX / 16);
        c.setChunkZ(rcz - offZ / 16);
    }

    private ChunkEntry makeEntry(LevelChunkPacket c, byte[] raw) {
        byte[] data = raw;
        boolean zipped = false;
        if (cfg.cacheCompress && raw.length > 256) {
            data = deflate(raw);
            zipped = true;
        }
        return new ChunkEntry(c.getChunkX(), c.getChunkZ(), c.getSubChunksLength(), c.getDimension(),
                c.getSubChunkLimit(), raw.length, data, zipped);
    }

    private void evict() {
        if (chunks.isEmpty()) return;
        if (pubRadius > 0) {
            double lim = pubRadius + cfg.evictMarginChunks * 16.0;
            chunks.values().removeIf(e -> Math.hypot(e.cx * 16 + 8 - pubX, e.cz * 16 + 8 - pubZ) > lim);
        }
        if (chunks.size() > cfg.cacheMaxChunks) {
            List<ChunkEntry> all = new ArrayList<>(chunks.values());
            all.sort(Comparator.comparingDouble(e -> -Math.hypot(e.cx * 16 + 8 - pubX, e.cz * 16 + 8 - pubZ)));
            for (int i = 0; i < all.size() - cfg.cacheMaxChunks; i++) {
                chunks.remove(key(all.get(i).cx, all.get(i).cz));
            }
        }
    }

    private void recordBlock(Vector3i real, BlockDefinition def, int layer) {
        if (real == null || chunks.isEmpty()) return;
        ChunkEntry e = chunks.get(key(real.getX() >> 4, real.getZ() >> 4));
        if (e != null) e.blocks.put(new PosLayer(real.getX(), real.getY(), real.getZ(), layer), new BlockUpd(real, def, layer));
    }

    private void recordTile(Vector3i real, NbtMap nbt) {
        if (real == null || nbt == null || chunks.isEmpty()) return;
        ChunkEntry e = chunks.get(key(real.getX() >> 4, real.getZ() >> 4));
        if (e != null) e.tiles.put(real, nbt);
    }

    private void recordSubChunkBlocks(UpdateSubChunkBlocksPacket packet) {
        for (org.cloudburstmc.protocol.bedrock.data.BlockChangeEntry entry : packet.getStandardBlocks()) {
            recordBlock(entry.getPosition(), entry.getDefinition(), 0);
        }
        for (org.cloudburstmc.protocol.bedrock.data.BlockChangeEntry entry : packet.getExtraBlocks()) {
            recordBlock(entry.getPosition(), entry.getDefinition(), 1);
        }
    }

    private static byte[] deflate(byte[] in) {
        Deflater d = new Deflater(Deflater.BEST_SPEED, true);
        try {
            d.setInput(in);
            d.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(in.length / 3 + 32);
            byte[] buf = new byte[8192];
            while (!d.finished()) out.write(buf, 0, d.deflate(buf));
            return out.toByteArray();
        } finally {
            d.end();
        }
    }

    private byte[] inflate(byte[] in, int rawLen) {
        Inflater i = new Inflater(true);
        try {
            i.setInput(in);
            byte[] out = new byte[rawLen];
            int n = 0;
            while (n < rawLen && !i.finished()) {
                int r = i.inflate(out, n, rawLen - n);
                if (r == 0 && (i.needsInput() || i.needsDictionary())) break;
                n += r;
            }
            return n == rawLen ? out : null;
        } catch (DataFormatException ex) {
            warnOnce("inflate", "區塊快取解壓失敗: " + ex);
            return null;
        } finally {
            i.end();
        }
    }

    // =====================================================================
    // ActionBar：真實座標
    // =====================================================================

    private void tickBar() {
        if (!havePos || localId == Long.MIN_VALUE) return;
        SetTitlePacket t = new SetTitlePacket();
        t.setType(SetTitlePacket.Type.ACTIONBAR);
        t.setText(barText(System.currentTimeMillis()));
        t.setFadeInTime(0);
        t.setStayTime(20);
        t.setFadeOutTime(0);
        t.setXuid("");
        t.setPlatformOnlineId("");
        t.setFilteredTitleText("");
        send(t);
    }

    private String barText(long now) {
        String s = cfg.abFormat
                .replace("{x}", num(lx)).replace("{y}", num(ly)).replace("{z}", num(lz))
                .replace("{facing}", facing()).replace("{dim}", dimName())
                .replace("{offx}", Integer.toString(offX)).replace("{offz}", Integer.toString(offZ));
        if (cfg.abMergeJava && javaBar != null && now - javaBarMs < cfg.abJavaKeepMs) s = s + cfg.abSep + javaBar;
        return s;
    }

    private String num(double v) {
        return String.format(Locale.ROOT, cfg.abNumber, v);
    }

    private String facing() {
        return cfg.facing[Math.floorMod(Math.round(yaw / 90f), 4)];
    }

    private String dimName() {
        return dim == 1 ? "nether" : dim == 2 ? "end" : "overworld";
    }

    // =====================================================================
    // 小工具
    // =====================================================================

    private void send(BedrockPacket p) {
        own.add(p);
        gs.sendUpstreamPacket(p);
    }

    private String name() {
        try {
            return gs.bedrockUsername();
        } catch (Throwable t) {
            return "?";
        }
    }

    // Throttle each diagnostic category independently so movement traffic
    // cannot suppress block-placement and teleport diagnostics.
    private final Map<String, Long> debugRateTimes = new HashMap<>();

    private void debugRate(String key, String msg, long everyMs) {
        if (!cfg.debug) return;
        long now = System.currentTimeMillis();
        Long last = debugRateTimes.get(key);
        if (last == null || now - last >= everyMs) {
            debugRateTimes.put(key, now);
            log.debug(msg);
        }
    }

    void javaHookReady(String pipeline) {
        log.info("[RealCoords] Java downstream hook active: " + name());
        if (cfg.debug) log.debug("[RealCoords] Java pipeline: " + pipeline);
    }

    void javaHookClosed() {
        log.info("[RealCoords] Java downstream hook closed: " + name());
    }

    Object javaOutbound(Object packet) {
        // Geyser→Java：Bedrock local coordinates are converted to real Java doubles here.
        if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket p) {
            double x = p.getX() + offX, z = p.getZ() + offZ;
            debugRate("java-move", String.format(
                    "[RealCoords][MOVE OUT] %s local=(%.4f, %.4f) -> java=(%.4f, %.4f) offset=(%d,%d)",
                    name(), p.getX(), p.getZ(), x, z, offX, offZ), 1000);
            return p.withX(x).withZ(z);
        }
        if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket p) {
            double x = p.getX() + offX, z = p.getZ() + offZ;
            debugRate("java-move-rot", String.format(
                    "[RealCoords][MOVE OUT] %s local=(%.4f, %.4f) -> java=(%.4f, %.4f) offset=(%d,%d)",
                    name(), p.getX(), p.getZ(), x, z, offX, offZ), 1000);
            return p.withX(x).withZ(z);
        }
        if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket p) {
            org.cloudburstmc.math.vector.Vector3d v = p.getPosition();
            return p.withPosition(org.cloudburstmc.math.vector.Vector3d.from(v.getX() + offX, v.getY(), v.getZ() + offZ));
        }
        if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket p) {
            Vector3i v = p.getPosition();
            Vector3i shifted = Vector3i.from(v.getX() + offX, v.getY(), v.getZ() + offZ);
            debugRate("block-place", String.format(
                    "[RealCoords][BLOCK USE] %s local=(%d,%d,%d) -> java=(%d,%d,%d) offset=(%d,%d)",
                    name(), v.getX(), v.getY(), v.getZ(), shifted.getX(), shifted.getY(), shifted.getZ(), offX, offZ), 0);
            return p.withPosition(shifted);
        }
        if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket p) {
            Vector3i v = p.getPosition();
            Vector3i shifted = Vector3i.from(v.getX() + offX, v.getY(), v.getZ() + offZ);
            debugRate("block-action", String.format(
                    "[RealCoords][BLOCK ACTION] %s action=%s local=(%d,%d,%d) -> java=(%d,%d,%d)",
                    name(), p.getAction(), v.getX(), v.getY(), v.getZ(), shifted.getX(), shifted.getY(), shifted.getZ()), 0);
            return p.withPosition(shifted);
        }
        return packet;
    }

    Object javaInbound(Object packet) {
        // Shift absolute entity positions before Geyser narrows them to Bedrock floats.
        if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket p) {
            double shiftedX = p.getX() - offX;
            double shiftedZ = p.getZ() - offZ;
            if (shiftedX != p.getX() || shiftedZ != p.getZ()) {
                debugRate("java-entity-spawn", String.format(
                        "[RealCoords][ENTITY SPAWN IN] %s id=%d java=(%.4f, %.4f) -> local=(%.4f, %.4f) offset=(%d,%d)",
                        name(), p.getEntityId(), p.getX(), p.getZ(), shiftedX, shiftedZ, offX, offZ), 1000);
                return p.withX(shiftedX).withZ(shiftedZ);
            }
        }
        if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket p) {
            org.cloudburstmc.math.vector.Vector3d v = p.getPosition();
            List<org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement> relative = p.getRelatives();
            boolean relX = relative.contains(org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement.X);
            boolean relZ = relative.contains(org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement.Z);
            double shiftedX = relX ? v.getX() : v.getX() - offX;
            double shiftedZ = relZ ? v.getZ() : v.getZ() - offZ;
            if (shiftedX != v.getX() || shiftedZ != v.getZ()) {
                debugRate("java-entity-teleport", String.format(
                        "[RealCoords][ENTITY TELEPORT IN] %s id=%d java=(%.4f, %.4f) -> local=(%.4f, %.4f) relative=(%s,%s) offset=(%d,%d)",
                        name(), p.getId(), v.getX(), v.getZ(), shiftedX, shiftedZ, relX, relZ, offX, offZ), 1000);
                return p.withPosition(org.cloudburstmc.math.vector.Vector3d.from(shiftedX, v.getY(), shiftedZ));
            }
        }
        if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket p) {
            org.cloudburstmc.math.vector.Vector3d v = p.getPosition();
            List<org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement> relative = p.getRelatives();
            double x = v.getX(), z = v.getZ();
            boolean relX = relative.contains(org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement.X);
            boolean relZ = relative.contains(org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement.Z);

            // 初次位置同步時，先在 double 精度下決定原點，避免 Geyser 先轉成 float。
            // Offset may have been reset by a respawn/dimension transition even when
            // havePos still describes the previous world. Reinitialize whenever the
            // current absolute position arrives with a zero origin.
            if (offX == 0 && offZ == 0) {
                ShiftConfig.Dim d = dimCfg();
                int nx = relX ? 0 : nextOff(offX, x, d);
                int nz = relZ ? 0 : nextOff(offZ, z, d);
                if (nx != 0 || nz != 0) {
                    offX = nx;
                    offZ = nz;
                    log.info(String.format("[RealCoords] %s: initial origin from Java position -> (%d,%d), raw=(%.3f, %.3f)",
                            name(), offX, offZ, x, z));
                }
            }
            double shiftedX = relX ? x : x - offX;
            double shiftedZ = relZ ? z : z - offZ;
            if (shiftedX != x || shiftedZ != z) {
                debugRate("java-teleport", String.format(
                        "[RealCoords][TELEPORT IN] %s java=(%.4f, %.4f) -> local=(%.4f, %.4f) relative=(%s,%s) offset=(%d,%d)",
                        name(), x, z, shiftedX, shiftedZ, relX, relZ, offX, offZ), 0);
                return p.withPosition(org.cloudburstmc.math.vector.Vector3d.from(shiftedX, v.getY(), shiftedZ));
            }
        }
        return packet;
    }

    private void warnOnce(String key, String msg) {
        if (warned.add(key)) log.warning("[RealCoords] " + msg);
    }

    private final Map<String, Long> rate = new HashMap<>();

    private void warnRate(String key, String msg, long everyMs) {
        long now = System.currentTimeMillis();
        Long last = rate.get(key);
        if (last == null || now - last >= everyMs) {
            rate.put(key, now);
            log.warning("[RealCoords] " + msg);
        }
    }
}
