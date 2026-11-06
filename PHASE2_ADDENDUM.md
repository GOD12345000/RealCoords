# RealCoords 第二階段追加指示（取代審查文件中的暫定數字與流程）

> 這份文件修正「第二階段設計審查文件」中的數字與缺漏。若兩者衝突，以本文件為準。
> 你無法連上實際伺服器：**沒有被 CI 或單元測試證明的事，不要宣稱已驗證**。
> 涉及 Geyser 內部行為的地方（第 7 節），請讀 Geyser 2.12.1 原始碼確認，並把結論寫進 `docs/phase2-notes.md`。

---

## 1. 精度門檻：改用 ULP 分析，捨棄 3,072 / 4,096

float 的相鄰間隔 = `2^(n-23)`，n 為座標所在的 2 的次方區間（`Math.ulp((float) v)`）。

| 座標 | 相鄰間隔 |
|---|---|
| 4,096 | 約 0.0005 格 |
| 65,536 | 約 0.008 格 |
| 131,072 | 約 0.016 格 |
| 1,048,576 | 0.125 格 |
| 8,388,608 | 1 格 |
| 16,777,216 | 2 格 |

結論：
- ±4,096 過於保守，沒有任何功能需要 0.0005 格精度。
- 3,072 會讓玩家每走約 3,000 格就遭遇一次整個世界重載，**不可接受**。
- 「提前觸發留安全餘裕」的理由不成立：精度是漸進惡化，沒有懸崖；重載只需數秒，
  最快的移動（鞘翅加火箭）數秒也只走數百格。
- 預設 `trigger-distance` 改為 **100000**，並在 README 附上上面的 ULP 對照表。

**不要新增獨立的 `precision.*` 設定區塊。** 現有 `trigger-distance` 判斷的 `|real - offset|` 本來就是
Geyser 內部座標，兩者是同一個概念；兩個設定只會讓使用者誤以為有「安全餘裕」。
設定載入時若 `trigger-distance` 大於 4,000,000，記錄一行警告（精度間隔已達 0.25 格以上）。

## 2. 兩種重新定位：免費時機與昂貴時機

### 2.1 免費時機（沿用階段 1 流程，只調門檻）
登入、重生、換維度、超過視距的伺服器傳送——Java／Geyser 本來就會重載，換原點沒有額外成本。

- 這些時機**每次都重新置中**：只要 `|real - offset| > origin-shift.recenter-on-reload`（預設 2048），
  就把偏移設為最接近的 `alignment` 倍數。
- 玩家在世界原點附近（`|real| < reset-distance` 且 offset 為 0）時行為不變，維持原版體驗。

### 2.2 昂貴時機（階段 2，連續移動）
只有在 `|real - offset| > trigger-distance`（預設 100000）時才考慮。新增設定：

```yaml
origin-shift:
  layer: java
  recenter-on-reload: 2048        # 免費時機的重新置中門檻
  trigger-distance: 100000        # 連續移動時的重新定位門檻
  reset-distance: 50000           # 必須小於 trigger-distance
  shift-while-moving: false       # 階段 2 總開關，預設關閉
  walk-fallback: none             # none | kick | reorigin
  transition-timeout-ticks: 100   # 重載逾時（5 秒），必須遠小於 Java keep-alive 逾時
  transition-cooldown-ticks: 600  # 兩次重載之間的最短間隔
```

`walk-fallback` 語意：
- `none`：超過門檻也不處理（精度逐漸變差，使用者自行承擔）。
- `kick`：踢出並提示重新連線（重新登入會走免費時機置中）。
- `reorigin`：執行完整重載。只有 `shift-while-moving: true` 時才有效。

舊 config 缺少新鍵時使用上述預設值，載入不可出錯。

## 3. 重載流程的核心修正：clientbound 閘門

審查文件只凍結了 serverbound，**漏了最重要的一半：重載期間 Java 伺服器仍持續送封包。**

### 3.1 狀態機
`NORMAL → PREPARE → RESET → REPLAY → DRAIN → NORMAL`，任何階段逾時或例外 → `FAILED`。
狀態只能在**下游連線的 Netty event loop** 上讀寫；所有排程任務也必須用同一個 event loop。

### 3.2 重載期間的規則
1. **serverbound 閘**：丟棄 Geyser 發往 Java 的「帶座標的移動封包」；keep-alive、聊天、
   玩家指令（潛行、衝刺）等非座標封包照常放行。
2. **clientbound 排隊**：重載期間從 Java 收到的封包，**用新偏移轉換後排進佇列**（設上限，例如 4096 個或記憶體上限），
   不要直接交給 Geyser。
   - 區塊封包：同時更新區塊快取，並從「待重放清單」移除同座標的舊項目（避免舊資料蓋新資料）。
   - 方塊更新、方塊實體資料：寫入覆蓋層，同時排隊。
   - 實體生成、移動、中繼資料等：更新實體日誌，同時排隊。
   - `Ping`、`KeepAlive` 類封包：必須即時處理並回覆，不可被佇列延遲到逾時。
3. 重放完成後，按到達順序放行佇列，再回到 `NORMAL`。
4. 佇列超限、逾時、或收到無法安全處理的封包 → `FAILED`（見第 6 節）。

### 3.3 凍結期間的玩家位置
合成傳送使用**凍結當下最後被接受的真實位置**（不是 Bedrock 客戶端當下位置），
這樣解除凍結後第一個移動封包才會與 Java 端狀態一致。
凍結期間 Bedrock 客戶端若移動，其位移被丟棄（客戶端會被合成傳送拉回）。
防呆閘（`safety.max-serverbound-jump`）在轉換期間與解除後 `transition-grace-ticks`（預設 20）內放寬，
避免正常的重新同步被誤判。
合成傳送會中斷慣性（例如鞘翅速度），這是已知取捨，寫進 README。

## 4. 合成封包與回應隔離

- **合成傳送 ID**：不要只用「負數／保留範圍」判斷。維護 `pendingSynthetic: Map<Integer, deadline>`，
  以**精確 ID 加逾時**比對；只吞掉 pending 中的 ID，吞掉後立即移除。
- 其他合成事件產生的回應也必須逐一列表處理，**列表要在 `docs/phase2-notes.md` 中根據原始碼寫出**，至少檢查：
  - `ServerboundAcceptTeleportationPacket`
  - 合成傳送之後 Geyser 回報位置的 `ServerboundMovePlayer*`
  - 重生後的 `ServerboundPlayerLoadedPacket`（若存在於 1.21.8 協議）
  - 重生相關的 `ServerboundClientCommandPacket`
- **區塊批次封包是陷阱**：
  - 真實的 `ClientboundChunkBatchStart/Finished` 要原樣交給 Geyser，
    Geyser 回的 `ServerboundChunkBatchReceivedPacket` 要**原樣送給 Java**
    （伺服器靠它控制送區塊的速度，吞掉會讓伺服器停止送區塊）。
  - 重放快取區塊時**不要送批次標記**，也就不會產生需要吞掉的回覆。
- 重載期間若 Java 送來**真實**的 `ClientboundPlayerPositionPacket`，排隊並用新偏移轉換，
  它的 teleport ID 是真實的，Geyser 的確認必須放行。
- 逾時未收到預期的合成回應 → `FAILED`，**不可**直接開閘讓未知封包通過。

## 5. 重放順序

```
1. 凍結 serverbound、開始 clientbound 排隊
2. 設定新偏移
3. 合成 Respawn（dataToKeep 設為保留全部屬性與中繼資料，需確認 1.21.8 欄位名稱與位元意義）
4. ClientboundSetChunkCacheCenter / SetChunkCacheRadius（視距）
5. 合成 ClientboundPlayerPositionPacket（pendingSynthetic 登記 ID）
6. 區塊：由近到遠、分批（resend-per-tick），不送批次標記
7. 區塊覆蓋層：方塊更新、方塊實體資料
8. 實體：依實體日誌重建（AddEntity → 中繼資料 → 裝備 → 屬性 → 效果 → 乘客關係 → 最新位置）；
   乘客關係最後建立，確保載具與乘客都已存在
9. 世界全域狀態：世界邊界、出生點、天氣、時間（以 Respawn 實際清掉的項目為準）
10. 放行 clientbound 佇列
11. 等待合成傳送確認，解除 serverbound 閘，進入寬限期
```

## 6. 失敗與恢復

- 任何逾時（`transition-timeout-ticks`）、佇列超限、記憶體上限、或重放資料缺失，
  → `FAILED`：**踢出該玩家並提示重新連線**。理由：同維度 Respawn 一旦送出就無法回復舊狀態，
  而重新登入會走免費時機置中，是最可靠的恢復方式。
- 玩家在重載中斷線：清除所有快取與排隊資料，不做額外處理。
- `FAILED` 時必須記錄原因與當時階段，方便使用者回報。
- 不要在狀態不完整時宣稱重新定位成功。

## 7. 推遲條件（這些情況下不啟動階段 2 重載）

Geyser 在小座標系統內持有這些狀態，重載會使其失效。偵測到時推遲到條件解除：
- 開著容器／工作台（Geyser 會為某些介面在世界中放假方塊）
- 騎乘載具
- 睡在床上
- 鞘翅滑翔中

推遲期間座標繼續增加，若超過 `trigger-distance` 的 8 倍仍無法重載，依 `walk-fallback` 處理。

**請閱讀並在 `docs/phase2-notes.md` 回答（含原始碼檔案與行號依據）：**
1. `JavaRespawnTranslator` 與 `DimensionUtils.switchDimension`：同維度重生清除了哪些狀態？
   哪些會被保留？是否會重置血量、屬性、背包、遊戲模式？
2. 上面清除的每一項，是否需要重放？對應到哪個封包？
3. Geyser 是否維護其他以座標為鍵的狀態（假方塊、`session` 內快取的位置、`ChunkCache`、`EntityCache`、
   進行中的挖掘／互動位置）？
4. 合成 Respawn 之後 Geyser 會自行送出哪些 serverbound 封包？

## 8. 測試要求（CI 必須通過）

延續審查文件第 8 節，並新增：
1. **ULP 測試**：對 ±30,000,000 的真實座標，套用 offset 後局部座標轉 float 再還原，
   誤差不超過 `Math.ulp` 的一半；`trigger-distance` 預設值下誤差小於 0.02 格。
2. **免費／昂貴時機**：登入與重生時門檻為 `recenter-on-reload`，連續移動時為 `trigger-distance`；
   邊界附近來回移動不會反覆重新定位（含冷卻）。
3. **clientbound 閘門**（`EmbeddedChannel`）：重載期間到達的區塊更新與實體移動在重放後仍被套用，
   且不會被舊快取覆蓋；`Ping`／`KeepAlive` 在重載期間仍即時回覆。
4. **批次封包**：真實 `ChunkBatch*` 的回覆被放行；合成重放不產生回覆。
5. **合成 ID**：只有 pending 中的 ID 被吞；不在 pending 的負數 ID 不被吞；逾時後 pending 清除並進入 `FAILED`。
6. **推遲條件**：容器開啟、騎乘中不啟動重載。
7. **失敗路徑**：逾時、佇列超限、中途斷線，狀態都被正確清除；`FAILED` 會踢出玩家。
8. **相容性**：舊 config（沒有新鍵）載入結果與階段 1 行為一致。

## 9. 交付規則

- 階段 2 保持獨立分支與 PR；`shift-while-moving` 預設 `false`，直到使用者回報實機驗收通過才建議開啟。
- 先交付「第 1、2.1 節的門檻調整」與「第 3、4 節的狀態機與測試」，再接 Geyser 的實際重置與重放。
  這樣即使重放部分還沒完成，階段 1 的改善也能先合併。
- 不確定 Geyser／MCProtocolLib API 時，寫探測日誌或在 PR 描述向使用者索取資訊，不要猜。
- README 與 PR 描述必須區分：「CI／單元測試已驗證」與「尚未實機驗證」。

## 10. 實機驗收（交給使用者，階段 2 開啟後）

在備份過的測試世界、座標約 X=-23,000,000、Z=17,000,000，`trigger-distance` 暫時調小（例如 2000）以便測試：

- [ ] 連續步行／飛行跨越門檻：畫面短暫重載後恢復，區塊與實體完整，沒有被傳回 0,0。
- [ ] 反方向移動、邊界附近來回移動：不會反覆重載（冷卻有效）。
- [ ] 重載期間 Java 端的方塊更新、生物移動沒有遺失。
- [ ] 開著箱子、騎船、睡覺時不會重載，條件解除後才處理。
- [ ] Java 端 `/data get entity @s Pos` 與 ActionBar 顯示一致。
- [ ] 重載中途斷線重連、故意讓重載逾時：玩家被踢出，重新登入後一切正常。
- [ ] 日誌沒有持續刷出「不合理的座標跳躍」。
