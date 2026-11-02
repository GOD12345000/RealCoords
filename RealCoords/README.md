# RealCoords（Geyser Extension）

讓基岩版玩家在 Java 世界的遠距離座標上不再出現精度異常：
- **浮動原點**：基岩版客戶端的座標超過設定距離後，把客戶端的座標系搬到玩家附近（偏移量為 16 的倍數），
  Geyser 與 Java 伺服器全程只看到真實座標。
- **真實座標 ActionBar**（快捷欄／經驗條上方），並關閉原版座標顯示。
- **防呆閘**：客戶端回報不合理的瞬間位移時丟棄，不會再被傳到 0,0 附近。

## 建置與安裝
```
gradle build          # 產物：build/libs/RealCoords-1.0.0.jar
```
放進 `plugins/Geyser-Velocity/extensions/`，重啟 Velocity。第一次啟動會在
`extensions/realcoords/config.yml` 產生設定檔。所有距離都在 config.yml。

`gradle.properties` 的 `geyserVersion` 要對成你實際的 Geyser 版本；
若 Geyser 核心是 Java 21 編譯的，把 `javaVersion` 改成 21。

## 驗證（建議照順序）
1. 進服後日誌應出現 `[RealCoords] hook active: <玩家名>`。沒有出現 = 封包管線掛載失敗（把 `safety.debug-log` 打開看 pipeline 結構）。
2. 螢幕下方出現座標列、原版座標消失。
3. `/tp @s 300000 100 300000`（或把 trigger-distance 暫時調小，例如 2000）：日誌會出現「原點 (0,0) -> (…)」，
   座標列仍顯示真實座標，周圍區塊、箱子、告示牌、實體都正常。
4. 走回原點附近，確認會歸零，且邊界不會反覆觸發。
5. 活塞、黏液塊推玩家、坐船、傳送門、重生各測一次。若有異常，日誌會有「不合理的座標跳躍」或
   「尚未轉換的封包」提示，把那幾行貼給我。

## 已知限制（請先看）
- **Geyser 內部用 float 存座標**。這個擴充只修正「基岩版客戶端」那一側的精度；在超過約 800 萬格的地方，
  Geyser 回報給 Java 的玩家座標本身會被量化（0.5～2 格），這一點擴充層無法改善。
- 尚未轉換的封包（遇到時會在日誌提示一次）：`UpdateSubChunkBlocks`、`SubChunk`、`LevelEventGeneric`、
  地圖（MapItemData）、指南針／磁石追蹤、畫（Painting）。實體重送時朝向會暫時歸零，直到下一次移動封包。
- 騎乘載具時換原點，載具座標靠實體重送修正，可能短暫抖動。
- 區塊快取只在「接近觸發距離」或「已有偏移」時啟用，預設每位玩家最多 2500 個區塊（壓縮保存）。
  可用 `chunk-cache.max-chunks` / `activate-margin` 調整。`activate-margin` 必須大於伺服器視距（格）。

## 我無法在這裡驗證、編譯時最可能需要微調的地方
此專案沒有在實際的 Geyser 環境編譯過。已通過語法檢查，並單獨測試了 NBT 尾端掃描器。
Cloudburst Protocol 的欄位名稱隨版本會變，若編譯報錯，通常是這幾處：
`LevelChunkPacket`（`setDimension` / `setSubChunkLimit`）、`PlayerBlockActionData` 建構子、
`SetTitlePacket#setFilteredTitleText`、`GameRulesChangedPacket#getGameRules`、
`BedrockSession#getPeer`、`SessionLoginEvent` 的匯入路徑、`extension.yml` 的 `api:` 欄位。
