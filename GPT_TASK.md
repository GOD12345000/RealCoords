# RealCoords 改版任務書：把座標轉換從 Bedrock 層移到 Java 協議層

> 給負責修改本 repo 的 AI／開發者。請完整讀完再動手。
> 前提：你可以讀 GitHub 上的 Geyser 與 MCProtocolLib 原始碼，但**無法連上實際的 Minecraft 伺服器**，
> 所以所有「已驗證」的宣稱只能來自 CI 編譯與單元測試。請不要宣稱做過實機測試。

---

## 0. 一句話目標

讓 Geyser **內部**永遠只看到靠近 0,0 的小座標（解決 float 精度），同時 Java 伺服器永遠看到真實座標，
Bedrock 客戶端也看到小座標。

## 1. 背景與實測證據

目前版本（Bedrock 層轉換）的做法：Geyser 內部用真實座標，只在 Bedrock 封包進出時做
`bedrock = real - offset`。

**實測問題**（使用者回報，座標 X/Z 約 -23,000,000 與 17,000,000）：
- 東西抖動、生物抽搐、有時放不了方塊。

**根本原因**：Geyser 內部用 `Vector3f`（float）儲存座標。float 在 16,777,216 以上相鄰值間隔 2 格，
在 8,388,608 以上間隔 1 格。Java 送來的 double 座標在 Geyser 轉成 float 的那一刻就被量化，
RealCoords 掛在其後，無法還原。回程（Geyser → Java）的玩家位置也是量化後的值，所以 Java 端
距離檢查偶爾會拒絕互動。

**結論**：偏移必須發生在 Geyser 轉成 float **之前**，也就是 Java 協議層。

## 2. 目標架構

```
Java 伺服器 (真實座標, double)
     │  ▲
     ▼  │
[Java 層轉換器]  clientbound: small = real - offset      serverbound: real = small + offset
     │  ▲
     ▼  │
Geyser 內部 (全部是小座標，float 夠用)
     │  ▲
     ▼  │
Bedrock 客戶端 (小座標)    ← 不再需要 Bedrock 層座標轉換
```

重點：
- 偏移量只在方塊單位的 16 倍數（`alignment`，沿用現有設定），所以區塊座標可整數平移。
- 偏移量是**每位玩家、每個維度**各自的狀態。
- 現有的 Bedrock 層座標轉換**必須停用**（否則會重複平移），但以下功能保留，因為與座標無關或只需很薄的 Bedrock 層：
  - ActionBar 真實座標（改用 Java 層追蹤到的真實座標）、`showcoordinates` 遊戲規則攔截、Java ActionBar 合併。
  - 防呆閘（改在 Java 層 serverbound 移動封包上執行）。
  - `ShiftConfig`、config.yml 載入邏輯。
- 新增 config：`origin-shift.layer: java | bedrock`（預設 `java`）。`bedrock` 保留舊行為方便對照，
  兩者不可同時啟用。

## 3. 第一步：先查清楚怎麼攔 Java 連線（不要猜）

在動手寫轉換前，先在 Geyser 2.12.1 原始碼確認，並把結論寫進 `docs/hook-notes.md`：

1. `GeyserSession#connect` 與 `DownstreamSession`：Java 端連線用哪個 MCProtocolLib `Session` 實作？
   在 Velocity 內建的 Geyser 上是否為 `LocalSession`（繼承 `TcpSession`）？Netty channel 怎麼取得？
2. Geyser 自己註冊的 `SessionListener`／處理流程在哪裡？**`SessionListener.packetReceived` 無法替換封包物件**
   （多數 MCPL 封包是不可變的），所以預期要走 **Netty pipeline**：
   在封包 decode 之後、`TcpSession`（通常名為 `manager`）之前插入 `ChannelDuplexHandler`，
   `channelRead` 看到 clientbound `Packet`，`write` 看到 serverbound `Packet`，可以用新實例取代。
3. 程式必須在連線建立後、第一個遊戲封包之前掛上；找出 `SessionLoginEvent`（Extension API）是否夠早，
   不夠就找替代時機。
4. 加入除錯輸出：`pipeline.names()`，並對「第一個攔到的 clientbound／serverbound 封包」各印一行日誌，
   以便使用者回報 hook 是否生效。

若確認 pipeline 攔截不可行，**停下來並在 PR／issue 說明原因與替代方案**，不要硬做。

## 4. 偏移狀態與換原點流程

沿用現有 `nextOff()` 的遲滯邏輯（`trigger-distance` / `reset-distance` / `alignment` / 每維度覆寫）。

### 階段 1（先做，風險低）：只在「本來就會整個重載」的時機換原點

Java 伺服器在這些時機本來就會重送所有區塊與實體，Geyser 狀態也會重置：
- 玩家登入（`ClientboundLoginPacket` 之後第一次收到位置）
- `ClientboundRespawnPacket`（含維度切換、死亡重生）
- 伺服器傳送玩家且距離超過視距（Java 會卸載舊區塊並送新區塊）

做法：在 clientbound 的**絕對位置封包**（`ClientboundPlayerPositionPacket`）上，**先**決定新偏移，
**再**轉換這個封包與後續封包。因為 Geyser 在這些時機本來就會清空區塊／實體快取，不需要重放。

注意：伺服器傳送玩家但距離**不**超過視距時，不要換原點（舊區塊還在，換了會全部錯位）；
等階段 2 處理。

預期效果：使用者 `/tp` 到 2300 萬格，或登入時在遠處，Geyser 內部立刻是小座標。

### 階段 2（後做，風險高）：玩家連續走動／飛行跨過門檻時換原點

此時 Java 不會重送資料，所以必須由我們**重建 Geyser 的世界狀態**：

1. **凍結**：進入換原點期間，丟棄 Geyser 發往 Java 的所有「帶座標的移動封包」，其餘（keep-alive、聊天等）照常。
2. 設定新偏移。
3. 對 Geyser 注入合成的 clientbound 封包，讓它清空狀態（研究
   `JavaRespawnTranslator`、`DimensionUtils.switchDimension`：同維度重生時它怎麼清區塊／實體；
   把它清掉的每一項列出來，並確認每一項是否需要重放）。
4. 注入 `ClientboundPlayerPositionPacket`（合成 teleport id，用保留範圍，例如負數或極大值）把玩家傳到新的小座標。
   **Geyser 會回 `ServerboundAcceptTeleportationPacket(合成 id)` 與後續確認封包；這些必須被攔下，
   不可送給 Java。** 同理，Geyser 因合成封包而產生的其他 serverbound 封包（例如重生後的 player loaded 類封包）
   也要建立「合成事件 → 預期回應」的對照表並吞掉。
5. **重放區塊**：Java 層快取原始 `ClientboundLevelChunkWithLightPacket`（以**真實**區塊座標為鍵，
   記憶體用 deflate 壓縮；只保留視距內並設每玩家上限，沿用 `chunk-cache.*` 設定），
   換原點後依距離排序、分批（`resend-per-tick`）送入 Geyser。
   區塊送出後發生的方塊變更（`BlockUpdate`、`SectionBlocksUpdate`、`BlockEntityData`）記為覆蓋層，重放區塊後補送。
6. **重放實體**：為視距內每個實體保存「重建它所需的封包日誌」：`AddEntity`、最新 `SetEntityData`、
   `SetEquipment`、`UpdateAttributes`、`UpdateMobEffect`、`SetPassengers` 等，重生後依序重放，
   位置以最新位置覆蓋。日誌要隨 `RemoveEntities` 清除並設上限。
7. 重放與世界相關的全域狀態：邊界（`InitializeBorder`）、出生點、天氣／時間等 Geyser 重置時會丟失的資料。
8. 解除凍結，並用一個旗標在 N tick 內忽略 Bedrock 客戶端到位前的移動（沿用現有 `hold` 概念）。

階段 2 若做不到穩定，**不要合併**；階段 1 可獨立發布。

## 5. 必須轉換的封包（清單需對照原始碼補完）

> 類別名稱來自 MCProtocolLib（Geyser 分支）的 1.21.8 協議。**請以實際原始碼為準**，下表只是起點。
> 對 Geyser 的 `translator/protocol/java/**` 全部 grep 一遍（找 `getPosition`、`BlockPos`、`Vector3`、
> `getX()/getZ()`、`chunkX/chunkZ`、`GlobalPos`），凡是帶世界座標的封包都要進表；
> 暫時不支援的要在日誌提示一次（沿用現有 `WATCH` 機制）。

### Clientbound（Java → Geyser）：`small = real - offset`

| 類別 | 欄位 | 備註 |
|---|---|---|
| `ClientboundPlayerPositionPacket` | x,z（僅絕對分量） | **相對旗標**的分量不可平移；delta 不變 |
| `ClientboundTeleportEntityPacket` | x,z | 同上，1.21.2+ 有相對旗標 |
| `ClientboundEntityPositionSyncPacket` | x,z | |
| `ClientboundMoveVehiclePacket` | x,z | |
| `ClientboundAddEntityPacket` | x,z | 畫、物品展示框也靠這個 |
| `ClientboundMoveEntityPos/PosRot` | — | 相對位移，**不要動** |
| `ClientboundLevelChunkWithLightPacket` | chunkX, chunkZ | `chunkX -= offX/16`；區塊內方塊實體是相對座標，不需改 |
| `ClientboundForgetLevelChunkPacket` | chunk x,z | |
| `ClientboundSetChunkCacheCenterPacket` | chunk x,z | |
| `ClientboundLightUpdatePacket` | chunk x,z | |
| `ClientboundChunksBiomesPacket` | 每項的 chunk x,z | |
| `ClientboundBlockUpdatePacket` | 方塊座標 | |
| `ClientboundSectionBlocksUpdatePacket` | 區段座標 x,z | 區段座標已是 /16 單位 |
| `ClientboundBlockEntityDataPacket` | 方塊座標 | NBT 內通常無座標，仍需檢查 |
| `ClientboundBlockEventPacket` / `BlockDestruction` | 方塊座標 | |
| `ClientboundLevelEventPacket` | 方塊座標 | |
| `ClientboundLevelParticlesPacket` | x,z | |
| `ClientboundSoundPacket`（含 named sound 版） | x,z | 注意協議用固定小數（×8）編碼 |
| `ClientboundExplodePacket` | center x,z | |
| `ClientboundSetDefaultSpawnPositionPacket` | 座標 | |
| `ClientboundOpenSignEditorPacket` | 座標 | |
| `ClientboundInitializeBorderPacket` / `SetBorderCenter` | center x,z | 世界邊界中心 |
| `ClientboundPlayerLookAtPacket` | 目標座標 | |
| `ClientboundLoginPacket` / `ClientboundRespawnPacket` | `lastDeathPos`（GlobalPos） | 同維度才平移 |
| `ClientboundSetEntityDataPacket` | 內含 BlockPos 的 metadata | 睡眠位置、終界水晶光束目標等；逐一查 Geyser 用到哪些 |
| 路徑點／定位條封包（1.21.6+） | 位置 | 若 Geyser 沒用到就列入不支援 |

### Serverbound（Geyser → Java）：`real = small + offset`

| 類別 | 欄位 | 備註 |
|---|---|---|
| `ServerboundMovePlayerPos/PosRot` | x,z | **在這裡執行防呆閘**（見第 6 節） |
| `ServerboundMoveVehiclePacket` | x,z | |
| `ServerboundPlayerActionPacket` | 方塊座標 | |
| `ServerboundUseItemOnPacket` | 方塊座標 | 游標位置是相對值，不可動 |
| `ServerboundPickItemFromBlockPacket` | 方塊座標 | |
| `ServerboundSignUpdatePacket` | 方塊座標 | |
| `ServerboundSetCommandBlockPacket` 等 | 方塊座標 | 命令方塊、結構方塊、拼圖方塊 |
| `ServerboundInteractPacket` | — | `INTERACT_AT` 的座標是相對實體，不可動 |

通用規則：偏移量是 16 的倍數，所以區塊座標平移為 `offX/16`、`offZ/16`；**絕對 vs 相對**要逐封包確認，
相對值一律不平移。

## 6. 防呆閘（重新設計）

在 serverbound 移動封包上：
- 還原後的真實位置與「上一個已接受的真實位置」距離超過 `safety.max-serverbound-jump`，
  且不在「等待到位／換原點」狀態 → **丟棄該封包的座標**（改送上一個已知位置或直接不送），記日誌，
  並以速率限制補送校正。
- 永遠不要讓看起來像「忘了加偏移」的座標（例如突然接近 0,0）送到 Java。

## 7. 設定檔變更（向下相容）

- 新增 `origin-shift.layer: java|bedrock`，預設 `java`。
- 新增 `origin-shift.shift-while-moving: false`（階段 2 開關，預設關；只有階段 2 穩定後才建議開）。
- 其餘設定鍵維持原名與預設值，舊 config 載入不可出錯，缺少的新鍵用預設值。

## 8. 測試要求（CI 必須通過）

無法開伺服器，所以用單元測試把轉換邏輯釘死：

1. **往返不變式**：對表中每一種封包，建立實例 → 套用 clientbound 轉換 → 再套用「對應的」serverbound 反向轉換，
   座標必須完全還原（使用 ±30,000,000 的座標與多個偏移量）。
2. **相對旗標**：`PlayerPosition`/`TeleportEntity` 在 X、Z 為相對時，數值必須原封不動。
3. **區塊座標**：偏移為 16 的倍數時，chunkX/Z 轉換無誤差；偏移不是 16 的倍數的設定必須被修正。
4. **Netty `EmbeddedChannel` 測試**：模擬 pipeline，確認 handler 能取代封包實例、
   合成封包的 serverbound 回應會被吞掉、非座標封包原樣通過。
5. **區塊快取**：重放順序（由近到遠）、覆蓋層補送、上限與驅逐。
6. 純邏輯單元（`nextOff`、遲滯、冷卻）要有測試，包含「邊界附近來回抖動不會反覆換原點」。

## 9. 流程與交付規則

- 每個階段獨立 commit／PR，CI 綠燈才算完成。
- 不要刪除現有功能；Bedrock 層程式碼保留但在 `layer: java` 時完全不啟用。
- **不確定 API 時不要猜**：寫小段探測程式與日誌，或回報需要使用者提供的資訊
  （例如 Geyser 啟動日誌、pipeline 名稱）。
- 更新 README：誠實標示「已通過 CI／單元測試」與「尚未實機驗證」的項目，
  並把「Geyser float 精度限制」那條改成新的現況說明（Java 層模式下 Geyser 內部座標很小，精度問題應消失；
  但仍待實機驗證）。
- 不要在 README 或回覆中宣稱「已在遊戲內測試」，除非使用者提供了測試結果。

## 10. 實機測試清單（交給使用者執行，驗收用）

在備份過的測試世界，座標約 X=-23,000,000、Z=17,000,000：

- [ ] 登入／`/tp` 到該座標：日誌顯示新偏移，ActionBar 仍顯示真實座標。
- [ ] 玩家與生物移動平滑，沒有抽搐。
- [ ] 放置／破壞方塊、開箱子、改告示牌正常；Java 端 `/data get entity @s Pos` 與 ActionBar 一致。
- [ ] 活塞、船、傳送門、重生正常。
- [ ] （階段 2）在門檻附近連續走動／飛行：換原點時畫面短暫重載後恢復，區塊與實體完整，沒有被傳回 0,0。
- [ ] 日誌沒有持續刷出「不合理的座標跳躍」或「尚未轉換的封包」。

## 11. 已知取捨（請寫進文件，不要隱藏）

- 階段 2 的重載會讓玩家看到短暫的重新載入畫面。
- 區塊與實體日誌的記憶體用量與玩家數成正比，需可設定上限。
- 地圖、磁石指南針、死亡位置等帶座標的物品資料可能需要額外處理；未處理者要在日誌與 README 列出。
- 依賴 Geyser／MCProtocolLib 內部結構，更新 Geyser 版本時需重新檢查。
