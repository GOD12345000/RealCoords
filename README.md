# RealCoords

RealCoords 是一個 Geyser Extension，目標是在不改動 Java 世界真實座標的前提下，讓 Bedrock 客戶端在遠距離座標下仍使用較小、較穩定的本地座標。

> **目前狀態：啟動 Agent 與官方 Geyser API 整合已初步實作；尚未完成執行期驗證，不可用於正式遊戲。**
>
> 使用獨立 Java Agent 在 JVM 啟動時掛接 Geyser 2.12.1 的 downstream listener 註冊點，不修改官方 Geyser JAR。RealCoords Extension 改為編譯對官方、未修改的 Geyser API。被動封包 bridge 尚未完成 Velocity + Geyser 的實際執行期驗證，也尚未啟用座標轉換。

## 目前開發基準

- RealCoords 分支：`feature/coordinate-origin-rebasing`
- Geyser 目標版本：`GeyserMC/Geyser@a973709`（Geyser 2.12.1-b1277）
- MCProtocolLib：26.3，目標來源 commit `b4fa8b23064bb85d7c7966cc2cd6bf369c9c1405`
- Java 編譯目標：21
- 使用情境：Velocity + Geyser；Java 後端環境目前為 1.21.8

## 新架構設計

核心原則：**Java 伺服器保留真實座標；Geyser 與 Bedrock 使用同一套玩家本地座標系。**

1. 為每位玩家／downstream session 建立穩定的 X/Z 原點。
2. Java 絕對座標封包在 Geyser 翻譯前轉成本地座標。
3. Geyser 的玩家、實體、區塊、方塊實體、快取等狀態必須一致地使用本地座標。
4. Bedrock 傳回的位置與互動座標保持本地座標，先讓 Geyser 完成驗證與處理。
5. Geyser 產生送往 Java 後端的封包時，再把絕對座標轉回真實座標。
6. 相對位移、方向、速度等非絕對位置資料不應套用原點平移。
7. 浮點運算先使用 `double`，直到必要時才轉成 Bedrock 所需的 `float`；原點盡可能對齊區塊邊界。

這不只是封包欄位的加減法。Geyser 內部所有會影響玩家、實體、區塊、方塊、NBT 與快取的座標狀態，都必須遵守同一座標空間。

## 已完成

- [x] 建立 `CoordinateTransform` 核心轉換類別。
- [x] 加入 double 精度、區塊對齊、整數座標與溢位檢查。
- [x] 建立 `CoordinateTransformTest`，並在 GitHub Actions 的 Java 21 `clean build` 中成功執行。
- [x] 查證 Geyser `a973709` 的 downstream 建立生命週期。
- [x] 查證 MCProtocolLib 26.3 的 listener、Netty pipeline 與 `ConnectedEvent` 行為。
- [x] 確認 TCP 與 Geyser `LocalSession` 共用 MCProtocolLib channel initializer。
- [x] 確認官方 Geyser API 沒有可保證第一批 Java 封包攔截順序的 downstream session hook。
- [x] 初步實作獨立 Java startup agent，在 `connectDownstream()` 第一個 listener 註冊前呼叫 RealCoords bridge。
- [x] Extension 改為編譯官方、未修改的 Geyser API。
- [x] 實作被動 Java downstream Netty packet bridge；尚待 CI 與真實伺服器執行期驗證。
- [x] 建立獨立 rotating file logger，避免一般診斷訊息混入 Velocity／Geyser console。
- [x] 停用舊的 Bedrock-only 座標轉換入口，避免新舊座標架構同時作用。

## 正在處理

### 玩家移動封包轉換（純轉換層，尚未接入正式管線）

已新增 `PlayerMovementPacketTransform`，目前只提供可獨立測試的封包轉換函式：

- Java → Geyser：轉換玩家傳送、實體生成／傳送／位置同步、載具位置、區塊載入／卸載／光照更新，以及方塊更新、方塊事件、爆炸中心、告示牌編輯等絕對座標。
- Geyser → Java：還原玩家與載具絕對位置、傳送確認，以及方塊互動／挖掘／方塊實體查詢／告示牌／命令方塊／拼圖與結構方塊等封包座標。
- 實體相對移動封包、相對位移、旋轉、速度、擊退向量與狀態封包不做原點平移。
- 不在此階段修改 Netty bridge。
- 已新增對應 JUnit 測試；仍需由 patched Geyser API 的 CI 實際編譯驗證。

**目前沒有啟用座標轉換。** 在原點生命週期、區塊／實體／互動座標同步完成前，不會把這個純轉換器接入正式封包管線。

### Geyser 初始化掛鉤

官方 Geyser Extension API 沒有公開 downstream MCProtocolLib session 的早期初始化事件。因此本分支改用獨立 Java startup agent：在 Geyser 2.12.1 的 `GeyserSession.connectDownstream()` 中，於第一個 `addListener()` 前呼叫 RealCoords bridge，讓 RealCoords 的 `SessionAdapter` 先於 Geyser 的 `ClientListener` 註冊。

- 不修改官方 Geyser JAR。
- 不使用 patched Geyser API。
- 不使用 tick-loop polling。
- Agent 目前針對 Geyser 2.12.1 的方法結構與兩次 listener 註冊進行嚴格檢查；結構不符時不注入。
- 此方案仍是針對指定版本的 runtime bytecode hook，不是 Geyser 官方 API；升級 Geyser 時必須重新驗證。

建置、JVM 啟動參數與部署方式見 [Startup Agent](docs/startup-agent.md)。

舊的 Geyser patch 與提案文件保留作為歷史調查資料，不是目前建置或部署流程的一部分。

## 分支安全約定

- 只在 `feature/coordinate-origin-rebasing` 開發。
- 不修改 `main`。
- 保留所有既有分支，不刪除分支。
- 未經測試不部署到正式伺服器。
