# RealCoords

**RealCoords** 是一個適用於 Geyser 的 Geyser Extension，目標是改善 Bedrock 玩家在 Java 世界遠距離座標下的客戶端座標精度問題。

> **階段一狀態：可供測試使用（Phase 1 test release）。** Java 協議層浮動原點已通過 CI，並由維護者在 Velocity + Geyser 2.12.1 的實際伺服器環境測試；目前回報在約 X=23,000,000、Z=17,000,000 的遠距離遊玩沒有遇到問題。這是目前測試範圍內的結果，不代表所有版本、封包與玩法均已完整驗證。請先備份世界，並閱讀下方已知限制。連續移動時自動重新設定原點（階段二）尚未完成。

English summary: RealCoords is an experimental Geyser extension that shifts Java protocol coordinates before Geyser converts them to floating-point values, keeping Geyser's internal coordinates small while the Java backend retains real world coordinates.

## 目錄

- [功能概覽](#功能概覽)
- [運作原理](#運作原理)
- [相容性與需求](#相容性與需求)
- [安裝與使用](#安裝與使用)
- [設定檔](#設定檔)
- [從原始碼編譯](#從原始碼編譯)
- [Fork 與貢獻](#fork-與貢獻)
- [測試清單與已知限制](#測試清單與已知限制)
- [疑難排解](#疑難排解)

## 功能概覽

- **每位玩家、每個維度獨立的浮動原點**：Java 模式在登入、重生／換維度與遠距離傳送時，選擇符合設定門檻與對齊規則的 X/Z 原點。
- **Java 協議層浮動原點（新模式）**：在 MCProtocolLib 封包進入 Geyser translator 前平移座標，讓 Geyser 內部處理小座標，Java 後端仍使用真實座標。
- **舊 Bedrock 模式**：保留舊版封包轉換，透過 `origin-shift.layer: bedrock` 選用；兩種模式不會同時啟用。
- **真實座標 ActionBar**：顯示真實 X/Y/Z 與面向，並可隱藏 Bedrock 原版座標列。
- **Java 層位置安全檢查**：對還原後的玩家移動座標套用 `safety.max-serverbound-jump`，異常跳躍封包會被丟棄。
- **舊模式區塊快取與重送**：`layer: bedrock` 保留原本的區塊快取、覆蓋層與分批重送機制；Java 模式階段 1 尚不需要重放。
- **部分方塊實體 NBT 座標轉換**：針對設定列出的 NBT 座標欄位進行平移。

RealCoords 是 Geyser Extension，不是 Paper／Spigot 插件，也不會直接安裝到 Java 後端伺服器。

## 運作原理

### 座標模型

RealCoords 使用每位玩家自己的水平偏移量 offsetX、offsetZ。Y 座標不做浮動原點平移。

轉換關係（`layer: java`）：

| 方向 | 轉換 |
|---|---|
| Java clientbound → Geyser 內部 | smallX = realX - offsetX；smallZ = realZ - offsetZ |
| Geyser serverbound → Java | realX = smallX + offsetX；realZ = smallZ + offsetZ |

例如玩家的真實位置是 (100000, 80, 100000)，若目前偏移量接近該位置，Bedrock 客戶端收到的位置就會靠近原點；Java 後端仍應看到 (100000, 80, 100000)。

偏移量會依 `origin-shift.alignment` 對齊指定格數，並非保證客戶端座標恰好等於 (0, 0)。

### 封包轉換流程

1. Geyser 建立 Bedrock 玩家連線，RealCoords 分別掛載 Java downstream hook 與薄型 Bedrock 輔助 hook。
2. **Java → Geyser**：Java downstream 的 Netty handler 在封包 decode 後、MCProtocolLib manager 前，將已支援的絕對座標減去偏移量。
3. Geyser translator 因此接收到小座標，避免先將遠距離 double 轉成 float 時產生量化誤差。
4. **Geyser → Java**：將已支援的玩家移動、方塊互動與編輯封包座標加回偏移量；玩家移動另經過安全跳躍檢查。
5. Bedrock 輔助 hook 不再平移座標，只負責追蹤小座標並加回偏移量以顯示真實座標 ActionBar、隱藏原版座標規則與合併 Java ActionBar。
6. `layer: bedrock` 才會啟用舊版完整 Bedrock 層轉換與區塊快取／重送。Java 模式的階段 1 僅在登入、重生／換維度與遠距離傳送等世界重載時機選擇原點；連續移動跨門檻的階段 2 尚未實作。

此設計的關鍵是：**不能只轉換玩家位置**。區塊、方塊更新、方塊實體 NBT、實體、出生點、聲音、粒子、互動封包等資料，只要含有世界座標，也可能需要套用相同偏移。尚未處理的資料類型會造成顯示或互動位置不一致。

## 相容性與需求

目前建置設定以以下環境為基準：

| 項目 | 目前建置目標 |
|---|---|
| Geyser | 2.12.1（建置依賴使用 2.12.1-SNAPSHOT） |
| Java 編譯版本 | Java 21 |
| Gradle | 8.10.2 |
| 代理平台 | 已在 Velocity 環境確認載入 |
| Java 後端 | 測試環境使用 1.21.8；不代表已驗證所有後端版本 |
| Bedrock | 依所安裝 Geyser 版本支援的 Bedrock 版本 |

RealCoords 使用部分 Geyser／Cloudburst 內部類別與封包處理方式，因此 **Geyser 版本更新可能需要修改程式碼**。請勿假設它能跨 Geyser 版本直接相容。

## 安裝與使用

### 1. 安裝 Geyser

先在 Velocity、Paper／Spigot 或其他受支援的平台安裝並正常啟動 Geyser。RealCoords 本身不能取代 Geyser。

### 2. 下載 RealCoords JAR

下載階段一測試版：開啟 [Phase 1 CI #342](https://github.com/GOD12345000/RealCoords/actions/runs/42184854276)，在頁面底部 **Artifacts** 下載 `RealCoords-JAR`，解壓後取得 JAR。這是對應階段一程式碼的 CI 產物；GitHub Actions Artifact 有保存期限，過期後請從相同分支重新執行建置。也可以依照下方「從原始碼編譯」自行產生 JAR。

解壓 Artifact，取得類似 RealCoords-1.0.0.jar 的檔案。

### 3. 安裝到 Geyser extensions

Velocity + Geyser 的典型路徑：

~~~text
plugins/
├── Geyser-Velocity.jar
└── Geyser-Velocity/
    └── extensions/
        └── RealCoords-1.0.0.jar
~~~

將 JAR 放進 Geyser 的 extensions 資料夾，然後完整重啟 Geyser／Velocity。若 Geyser 安裝在其他平台，請使用該平台的 Geyser data folder 下的 extensions 目錄。

### 4. 確認載入

啟動紀錄應包含類似訊息：

~~~text
[geyser]: Enabled extension RealCoords
[geyser]: Loaded 1 extension(s)
[RealCoords] 已origin-shift=true, actionbar=true, chunk-cache=true
~~~

Velocity 主控台可使用：

~~~text
geyser extensions
~~~

確認清單中出現 RealCoords。Bedrock 玩家連線後，若封包管線掛載成功，應看到：

~~~text
[RealCoords] Java coordinate transform installed; pipeline=[...]
[RealCoords] Java hook first clientbound packet: ...
[RealCoords] Java hook first serverbound packet: ...
~~~

第一次啟動後，設定檔會由 Extension 產生在 Geyser extensions 的資料目錄中，通常為：

~~~text
plugins/Geyser-Velocity/extensions/realcoords/config.yml
~~~

實際路徑以 Geyser 的資料目錄及啟動紀錄為準。

### 5. 安全測試方式

1. 先備份世界，最好使用獨立測試世界。
2. 暫時把主世界 trigger-distance 調低，例如 2000，並把 safety.debug-log 設為 true。
3. 讓 Bedrock 玩家連線，確認 ActionBar 顯示真實座標。
4. 在測試世界移動或由管理員傳送到門檻外，觀察偏移是否觸發、區塊是否正常，以及 Java 端看到的座標是否仍是真實位置。
5. 測試方塊互動、容器、實體、傳送、重生與維度切換。
6. 測試完成後恢復正式門檻，並關閉除錯紀錄。

不要只用 ActionBar 是否顯示正常來判斷成功；還要核對 Java 端座標、區塊、方塊互動與其他遊戲資料。

## 設定檔

以下列出目前 config.yml 的主要參數。實際預設值以 src/main/resources/config.yml 為準。

### origin-shift：浮動原點

| 參數 | 目前預設值 | 說明 |
|---|---:|---|
| enabled | true | 全域啟用浮動原點 |
| layer | java | `java`：Java 協議層；`bedrock`：舊版相容模式 |
| alignment | 256 | X/Z 偏移量的對齊間隔（格） |
| recenter-on-reload | 2048 | 登入／重生／維度切換／遠距傳送等免費重載事件的重新置中門檻（格） |
| trigger-distance | 100000 | 連續移動模式的精度觸發距離（格）；預設值依 float 精度設定 |
| reset-distance | 50000 | 已偏移後，允許重設偏移的距離（格）；必須小於 trigger-distance |
| cooldown-ticks | 100 | 舊 Bedrock 模式兩次偏移操作之間的冷卻時間 |
| transition-timeout-ticks | 100 | Java 模式一次 transition 的最長時間 |
| transition-cooldown-ticks | 600 | Java 模式一次 transition 完成後，禁止再次觸發移動式 re-origin 的最短間隔 |
| transition-grace-ticks | 20 | Java 模式 transition 完成後，使用較大但有上限的移動距離限制的寬限時間 |
| ack-timeout-ticks | 60 | 舊 Bedrock 模式等待客戶端位置確認的逾時時間 |
| ack-tolerance | 4.0 | 舊 Bedrock 模式確認位置時容許的誤差 |
| dimensions.*.enabled | true | 個別維度是否啟用 |

Minecraft 正常 tick rate 約為每秒 20 ticks；例如 100 ticks 約為 5 秒。觸發與重設距離形成遲滯區間，避免玩家在同一門檻附近反覆切換。登入、重生、維度切換及遠距傳送本來就會使 Geyser 重載世界，因此可使用較小的 `recenter-on-reload` 免費重新置中；連續移動則使用較大的 `trigger-distance`，避免頻繁重載。真實座標仍在 `reset-distance` 範圍內時會保留原本不偏移的行為。Java 模式階段 1 不使用 `cooldown-ticks`、`ack-timeout-ticks` 與 `ack-tolerance`；這三項保留給舊 Bedrock 模式。


### float 座標精度與 ULP

Java／Geyser 會在部分座標處理路徑使用 IEEE 754 `float`。數值越大，相鄰可表示浮點數的間距（ULP）越大；因此應在座標精度開始明顯下降前重新置中。

| 局部座標量級 | `Math.ulp((float) 座標)` | 約略間距 |
|---:|---:|---:|
| 4,096 | 0.00048828125 | 0.0005 格 |
| 65,536 | 0.0078125 | 0.008 格 |
| 131,072 | 0.015625 | 0.016 格 |
| 1,048,576 | 0.125 | 0.125 格 |
| 8,388,608 | 1 | 1 格 |
| 16,777,216 | 2 | 2 格 |

預設 `trigger-distance: 100000` 時，局部座標的 float ULP 約為 0.0078125 格，四捨五入轉換誤差最多約半個 ULP。若將 `trigger-distance` 設定為大於 4,000,000，載入設定時會記錄警告，因為此時 float ULP 已達 0.25 格以上。

### safety：位置安全檢查

| 參數 | 目前預設值 | 說明 |
|---|---:|---|
| max-serverbound-jump | 64.0 | 一般狀態下，玩家相鄰兩個已接受真實 X/Z 位置的最大水平距離 |
| transition-grace-max-jump | 256.0 | PREPARE／RESET／REPLAY／DRAIN 與寬限期使用的最大水平距離；必須不小於 max-serverbound-jump，缺少時使用 256.0，無效或過小時自動調整為 max-serverbound-jump |
| correction-interval-ticks | 10 | 舊 Bedrock 模式補送位置修正封包的最短間隔 |
| debug-log | false | 是否輸出額外除錯資訊 |

**Java 模式 transition 計時設定：** `origin-shift.transition-cooldown-ticks`（預設 600）是一次完整 re-origin 完成後，禁止再次觸發移動式 re-origin 的最短間隔；它不會阻止正常移動封包送往 Java。舊的 `origin-shift.cooldown-ticks`（預設 100）只供舊 Bedrock layer 的偏移／確認流程使用，兩者用途不同。`origin-shift.transition-grace-ticks`（預設 20）是 transition 完成後套用較寬移動上限的時間；transition 進行中也使用同一上限。寬限期間的上限由 `safety.transition-grace-max-jump` 控制（預設 256 格），不是停用防呆；一般時段則使用 `safety.max-serverbound-jump`（預設 64 格）。距離一律比較上一個已接受的真實 X/Z 位置，超限封包會被丟棄，不會送往 Java。

### chunk-cache：區塊快取

這組快取／重送設定只供 `layer: bedrock` 的舊模式使用；Java 模式階段 1 依賴伺服器原本的世界重載，不重放區塊。

| 參數 | 目前預設值 | 說明 |
|---|---:|---|
| enabled | true | 啟用區塊快取 |
| compress | true | 壓縮快取資料 |
| max-chunks | 2500 | 每位玩家最多保留的快取區塊數 |
| activate-margin | 1500 | 距離觸發門檻的預先啟用範圍（格） |
| resend-per-tick | 24 | 每 tick 最多重送的區塊數 |
| evict-margin-chunks | 4 | 快取清理使用的區塊緩衝 |

max-chunks 是**每位玩家**上限；玩家數增加時，整體快取用量也會增加。activate-margin 應大於伺服器視距所需的水平範圍。提高重送速度可能增加短時間網路與處理負載。

### nbt-shift-keys：NBT 座標欄位

指定 NBT 內需要平移的欄位名稱：

- x：X 座標欄位。
- z：Z 座標欄位。
- triples：包含三維座標的欄位。

目前預設涵蓋一般 x、z、活塞位置欄位，以及 AttachedBlocks、BreakBlocks。這些名稱必須符合實際 NBT 結構；除非清楚了解資料格式，否則不要任意修改。

### actionbar：真實座標列

| 參數 | 目前預設值 | 說明 |
|---|---|---|
| enabled | true | 啟用自訂 ActionBar |
| hide-vanilla-coordinates | true | 隱藏原版座標顯示 |
| interval-ticks | 10 | ActionBar 更新間隔 |
| format | 含 {x}、{y}、{z}、{facing} | 顯示格式與顏色 |
| number-format | %,.1f | 數字格式 |
| merge-java-actionbar | true | 是否合併 Java 伺服器 ActionBar |
| java-actionbar-keep-ms | 3000 | Java ActionBar 訊息保留時間（毫秒） |
| separator | &8| &r | 合併訊息分隔符 |
| eye-height | 1.62 | Y 座標顯示使用的眼睛高度修正 |
| facing | 四個方向文字 | 方向顯示文字 |

格式可使用 & 顏色代碼；{x}、{y}、{z} 代表真實座標，{facing} 代表面向方向。

## 從原始碼編譯

### 環境

- Git
- JDK 21
- Gradle 8.10.2（CI 會自動安裝）
- 可連線至 Maven Central 與 OpenCollab Maven repository

### 本機編譯

~~~bash
git clone https://github.com/GOD12345000/RealCoords.git
cd RealCoords/RealCoords
gradle --no-daemon clean build
~~~

JAR 產物：

~~~text
RealCoords/build/libs/RealCoords-1.0.0.jar
~~~

目前 gradle.properties 已設定 Geyser 2.12.1-SNAPSHOT 與 Java 21。若要支援其他 Geyser 版本，請先確認 API／Cloudburst Protocol 相容性，再修改依賴版本並實際編譯與測試。

### GitHub Actions

Push 到 main、提交 Pull Request，或手動執行 **Actions → Build RealCoords**，都會執行建置。成功時會產生 RealCoords-JAR Artifact（保留 14 天）。

工作流程只讀取 repository 內的 Gradle 專案，不依賴原始碼 ZIP，也不依賴原作者的個人路徑或 Secret。

## Fork 與貢獻

1. 在 GitHub 點選 **Fork**，或 Clone 你的 fork。
2. 確認 fork 的 **Actions** 功能已啟用。
3. 修改 RealCoords/ 下的 Java／Gradle／資源檔。
4. Push 到 fork 的 main，或開 Pull Request。
5. 到 Actions 查看 Build RealCoords；若失敗，先檢查完整編譯 log。
6. 若修改 Geyser 依賴版本，請同步確認 gradle.properties、extension.yml 的 API 版本及程式碼使用的 API。

第一次整理 repository 時，工作流程會把舊的 RealCoords-source.zip 解壓成正常目錄、套用原本建置所需的 Geyser 2.12.1 API 相容性調整，並提交可瀏覽的原始碼。後續 fork 不需要 ZIP，也不需要執行這個一次性整理步驟。

## 測試清單與已知限制

### 階段一已完成／已修正

- [x] Java downstream hook 在 Geyser translator 前攔截 Java 協議封包，避免遠距離座標先轉成 float 才失去精度。
- [x] Java clientbound 與 serverbound 的已支援座標封包採用相反方向偏移，Java 後端仍使用真實世界座標。
- [x] 登入、重生／維度切換及遠距離絕對傳送時選擇／更新每位玩家、每個維度的浮動原點。
- [x] 已處理玩家位置、區塊與光照、生物群系、方塊更新、方塊實體資料、部分實體／粒子／聲音／爆炸／世界邊界等座標資料，以及多種互動封包。
- [x] 修正玩家移動與載具移動的座標還原路徑，並處理傳送確認封包的座標還原。
- [x] 保留舊 Bedrock layer 模式，並保留真實座標 ActionBar、Java ActionBar 合併及移動安全檢查。
- [x] 維護者實機回報：在約 X=23,000,000、Z=17,000,000 的遠距離場景，目前測試版本遊玩未遇到問題。此為已回報的測試結果，不等於完整相容性認證。

### 已由 CI／原始碼檢查確認

- [x] GitHub Actions 編譯 Java 21 專案並執行 JUnit 測試。
- [x] Java downstream hook 的 pipeline 插入位置與連線時序已依 Geyser／MCProtocolLib 原始碼調查。
- [x] Java 模式已接上 Java protocol transformer 與 Bedrock 輔助 ActionBar hook。
- [x] 單元測試涵蓋初始原點、門檻抖動、相對／混合位置旗標、分批取得絕對 X/Z、跨維度原點隔離、玩家移動往返、Netty EmbeddedChannel 封包替換、區塊座標與異常移動安全閘。

### 尚待擴大實機驗收

- [ ] 覆蓋更多 Geyser／Bedrock／Java 後端版本與多人同時遊玩情境。
- [ ] Java 模式遠距登入／傳送後，確認 Geyser 內部與 Bedrock 客戶端座標維持小數值，Java 後端仍看到真實座標。
- [ ] 遠距離觸發偏移後，Bedrock 與 Java 端座標一致。
- [ ] 重設距離、冷卻時間與客戶端確認流程正常。
- [ ] 偏移後區塊完整載入，沒有空洞、錯位或持續閃爍。
- [ ] 區塊快取在快速移動、維度切換與多人情況下能正確清理。
- [ ] 箱子、熔爐、告示牌及其他方塊實體互動正常。
- [ ] 活塞、紅石、黏液塊與蜂蜜塊相關互動正常。
- [ ] 怪物、掉落物、玩家與其他實體的位置及朝向正常。
- [ ] 船等載具在偏移切換時沒有持續抖動或位置錯誤。
- [ ] 傳送、重生、地獄傳送門與其他維度切換正常。
- [ ] 地圖、指南針／磁石追蹤、畫作與所有特殊世界事件正常。
- [ ] 多名 Bedrock 玩家同時遊玩時，CPU、記憶體與網路負載可接受。

### 尚未完成的功能與已知限制

1. **階段二：連續移動自動重新設定原點尚未實作**：目前只在登入、重生／換維度及符合條件的遠距離絕對傳送等世界重載時機更新原點。玩家持續步行／飛行跨越門檻時，不會自動完成完整的重新原點流程。
2. **階段二的世界狀態重建尚未完成**：目前 Java 模式的 chunk cache 以擷取／保存為主，尚未串接原點切換後的完整區塊重播；合成重設封包、重播批次、完整實體狀態及世界全域狀態恢復仍待實作。
3. **未完整轉換的封包**：Java 模式目前只改寫已明確支援的封包。尚未處理的絕對座標封包與部分特殊封包，可能在偏移狀態下錯位；地圖圖示座標是 map-local，刻意不套用世界原點偏移。login／respawn 的 `lastDeathPos` 會在原點確定後更新 Geyser 內部 metadata，但若死亡標記與玩家相距過遠，仍可能超出本地座標範圍並產生警告。命令方塊、結構方塊與拼圖方塊位置已加入轉換，但仍需實機核對。
4. **Geyser 浮點精度與其他版本相容性**：目前測試環境已回報遠距離遊玩正常，但仍需在更多 Geyser／Bedrock／Java 版本組合確認。
5. **實體朝向**：部分實體重新送出時，朝向可能暫時歸零，直到收到下一次移動更新。
6. **載具**：騎乘載具時發生原點切換，可能短暫抖動。
7. **區塊快取**：快取上限是每位玩家各自計算；多人使用時需實際監控整體記憶體（此項主要適用於舊 Bedrock 模式）。
8. **內部 API 相依**：封包類別與欄位可能隨 Geyser／Cloudburst Protocol 更新而變動。建置成功不代表執行時行為一定相容。

以上限制是目前已知的開發狀態，不代表每一項都已在所有版本重現；請以測試結果與 issue 回報補充。

## 疑難排解

### Geyser 沒有顯示 RealCoords

- 確認 JAR 放在 Geyser 的 extensions 目錄，不是 Velocity 的一般 plugins 目錄，也不是 Paper 後端的 plugins 目錄。
- 確認 Geyser 本體已成功載入。
- 完整重啟 Geyser／Velocity，查看 extension 載入錯誤。

### 有載入，但 Java hook 沒有安裝

- 確認是 Bedrock 玩家經由這個 Geyser 實例連線。
- Java 模式應看到 `Java coordinate transform installed`、`first clientbound packet` 與 `first serverbound packet` 日誌。
- 若使用舊 Bedrock 模式，則查看舊版 `hook active` 訊息。
- 若 Java hook 逾時或找不到 manager handler，請提供從玩家登入開始的完整相關 log。

### 偏移後區塊或互動異常

- 先停止在正式世界測試，保留 log 與重現步驟。
- 記錄維度、真實座標、觸發門檻、玩家數及發生問題的動作。
- 附上 Geyser 版本、Bedrock 版本、Velocity／後端版本與相關 log。

## 回報問題

回報時請提供：

- Geyser 版本與平台（Velocity、Paper 等）。
- Java 後端版本及 Bedrock 客戶端版本。
- 是否只有 Bedrock 玩家受影響。
- 發生問題時的維度與座標。
- 重現步驟及相關主控台 log。
- 使用中的 RealCoords 設定（請移除任何私人資訊）。
