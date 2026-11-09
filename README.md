# RealCoords

RealCoords 是一個 **Geyser Extension**，為經由 Geyser 連線的 Bedrock 玩家提供浮動原點座標轉換，降低 Geyser 在處理遠距離世界座標時遇到的浮點精度問題。

> **v2.0.0 狀態：第二階段基礎整合版／測試發行。** Java 協議層座標轉換、第一階段的登入／重生／遠距傳送置中，以及第二階段已合併的 transition 狀態管理與封包延後基礎已包含在 main。CI #412 已通過。但這不代表連續移動 re-origin 的完整世界重設與重播已完成，也不代表所有遊戲情境已實機驗收。**`origin-shift.shift-while-moving` 預設仍為 `false`，目前不要在正式伺服器啟用。**

English summary: RealCoords is an experimental Geyser extension that translates supported Java protocol world coordinates before Geyser's translators process them, keeping Geyser's coordinate space near the local origin while restoring real coordinates toward the Java server.

## 目前版本與狀態

| 項目 | 狀態 |
|---|---|
| 發行版本 | 2.0.0（本分支準備發行；以 GitHub Release 發布完成為準） |
| Geyser 建置目標 | 2.12.1-SNAPSHOT / Extension API 2.12.1 |
| Java | 21 |
| Gradle | 8.10.2 |
| CI | [main Build #412](https://github.com/GOD12345000/RealCoords/actions/runs/42580739373) 通過 |
| 實機環境回報 | 維護者曾回報在 Velocity + Geyser 2.12.1、約 X=23,000,000 / Z=17,000,000 的測試場景遊玩未遇到問題；不等於完整相容性認證 |

## 功能概覽

- 每位玩家、每個維度獨立保存 X/Z 浮動原點。
- Java 協議層 Netty hook 在 Geyser translator 前轉換已支援的座標封包，避免遠距離座標先轉成 float 才失去精度。
- 已支援的 clientbound 座標由真實座標減去 offset；對應 serverbound 座標加回 offset，Java 後端仍使用真實世界座標。
- 登入、重生／維度切換及符合條件的遠距離傳送會選擇或更新原點。
- 支援真實座標 ActionBar、Java ActionBar 合併、舊 Bedrock layer 相容模式及移動安全檢查。
- 第二階段已合併 transition 狀態管理、封包延後／閘門相關基礎；**完整連續移動重定位流程仍未完成實機驗收**。

RealCoords 是 Geyser Extension，不是 Paper／Spigot 插件。請安裝在 Geyser 的 extensions 目錄，不是 Java 後端的 plugins 目錄。

## 運作原理

RealCoords 為每位玩家、每個維度維護水平偏移量 `offsetX`、`offsetZ`；Y 不做浮動原點平移。

| 封包方向 | 座標轉換 |
|---|---|
| Java clientbound → Geyser | `smallX = realX - offsetX`；`smallZ = realZ - offsetZ` |
| Geyser serverbound → Java | `realX = smallX + offsetX`；`realZ = smallZ + offsetZ` |

Java downstream Netty handler 在封包 decode 後、MCProtocolLib manager／Geyser translator 前處理已支援的封包。偏移量依 `origin-shift.alignment` 對齊；區塊座標因此可按整數區塊平移。未列入支援範圍的座標欄位仍可能造成顯示或互動錯位。

### 浮點精度參考

IEEE 754 float 的 ULP 會隨數值增大：

| 局部座標量級 | float ULP（相鄰值間距） |
|---:|---:|
| 4,096 | 0.00048828125 |
| 65,536 | 0.0078125 |
| 131,072 | 0.015625 |
| 1,048,576 | 0.125 |
| 8,388,608 | 1 |
| 16,777,216 | 2 |

預設 `trigger-distance: 100000` 時，float ULP 約為 0.0078125 格。這是局部座標精度參考，不是對所有封包／玩法正確性的保證。

## 安裝

1. 安裝並先正常啟動 Geyser（Velocity、Paper 等受支援平台）。
2. 從 [GitHub Releases](https://github.com/GOD12345000/RealCoords/releases) 下載對應版本的 `RealCoords-2.0.0.jar`。
3. 將 JAR 放入 Geyser data folder 下的 `extensions/`。Velocity 常見路徑如下：

```text
plugins/
├── Geyser-Velocity.jar
└── Geyser-Velocity/
    └── extensions/
        └── RealCoords-2.0.0.jar
```

4. 完整重啟 Geyser／Velocity，確認啟動日誌顯示 RealCoords extension 已載入。
5. 初次使用先在備份過的測試世界驗證；不要直接把未驗收版本投入正式世界。

設定檔通常位於 Geyser extensions 的資料目錄，例如 `plugins/Geyser-Velocity/extensions/realcoords/config.yml`；實際位置以啟動日誌及平台資料目錄為準。

## 重要設定

以下為目前資源檔 `RealCoords/src/main/resources/config.yml` 中的主要預設值。舊設定檔可能缺少後續新增的鍵；請以新版本產生的設定檔及啟動警告為準。

| 設定 | 預設值 | 說明 |
|---|---:|---|
| `origin-shift.enabled` | `true` | 啟用浮動原點 |
| `origin-shift.layer` | `java` | `java` 為 Java 協議層；`bedrock` 為舊相容模式 |
| `origin-shift.shift-while-moving` | `false` | 連續移動時嘗試重新定位；目前保持關閉 |
| `origin-shift.alignment` | `256` | X/Z 偏移對齊單位，需為 16 的倍數 |
| `origin-shift.recenter-on-reload` | `2048` | 登入／重生／維度切換／合適遠距傳送時的置中門檻 |
| `origin-shift.trigger-distance` | `100000` | 連續移動模式精度觸發門檻 |
| `origin-shift.reset-distance` | `50000` | 回到此距離內時可重設偏移；需小於 trigger-distance |
| `origin-shift.transition-timeout-ticks` | `100` | transition 逾時上限 |
| `origin-shift.transition-cooldown-ticks` | `600` | transition 之間的最短間隔 |
| `origin-shift.transition-grace-ticks` | `20` | transition 後較寬移動限制的寬限時間 |
| `origin-shift.walk-fallback` | `none` | 延後重定位超過限制時的處理策略 |
| `safety.max-serverbound-jump` | `64.0` | 一般狀態下允許的水平移動距離上限 |
| `safety.transition-grace-max-jump` | `256.0` | transition／寬限期使用的水平移動上限 |
| `safety.debug-log` | `false` | 額外除錯日誌 |

**請勿因為 2.0.0 已發布就打開 `shift-while-moving`。** 第二階段的狀態機與封包閘基礎已合併，但完整的 Geyser client-side reset、區塊／方塊更新／實體重播與世界狀態恢復，仍需逐項完成及實機驗收。

舊 Bedrock layer 的 `chunk-cache`、`cooldown-ticks`、`ack-timeout-ticks`、`ack-tolerance` 等設定仍保留給相容模式；不要把它們誤認為 Java layer 已經完成完整重播。

## 建置與 CI

需求：JDK 21、Gradle 8.10.2，以及 Maven Central／OpenCollab repository 網路連線。

```bash
git clone https://github.com/GOD12345000/RealCoords.git
cd RealCoords/RealCoords
gradle --no-daemon clean build
```

JAR 位於 `RealCoords/build/libs/`。GitHub Actions 的 **Build RealCoords** workflow 會執行編譯與測試，成功時上傳 `RealCoords-JAR` artifact（保存 14 天）。CI 通過代表該次自動化建置與測試通過，不代表完成所有 Bedrock／Velocity 實機驗收。

## 測試狀態與已知限制

### 已完成／已合併

- Java downstream hook 與 Java protocol 座標轉換架構。
- 已支援封包的雙向 X/Z 座標轉換與玩家移動安全檢查。
- 登入、重生／維度切換與合適遠距傳送時的原點選擇。
- 第二階段 transition 狀態管理與封包延後／閘門基礎。
- CI #412 對 main 合併提交 `c8e7aec7293547c25916665117cdf22f658eed4f` 建置成功。

### 尚未完成／不可宣稱已驗收

- 連續步行／飛行跨越門檻時，完整自動 re-origin 的端到端實機驗收。
- Geyser 同維度重設後，完整重播視距內區塊、光照、生物群系、方塊更新與方塊實體資料。
- 完整實體狀態重建，以及世界邊界、出生點、天氣、時間等世界狀態恢復。
- transition 期間各類真實封包與合成封包回應的完整覆蓋。
- 更多 Geyser／Bedrock／Java 版本、多玩家、載具、紅石、地圖與特殊玩法相容性。

### 已知風險

1. Java layer 只轉換已明確支援的封包；未覆蓋的絕對座標欄位可能錯位。
2. Geyser／Cloudburst Protocol／MCProtocolLib 內部 API 變更可能要求修改程式碼。
3. transition 若沒有完整世界狀態重建，可能造成區塊、實體或互動狀態不一致。
4. 原點切換可能中斷移動慣性；連續移動流程未經驗收前保持關閉。
5. 舊 Bedrock layer 與 Java layer 的行為及設定用途不同，請勿混用其功能假設。

## 回報問題

請附上 Geyser 版本與平台、Java 後端版本、Bedrock 版本、RealCoords 設定（移除私人資訊）、發生座標、重現步驟及相關完整日誌。遇到區塊或互動錯位時，先停止在正式世界測試並保留紀錄。
