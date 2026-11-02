# RealCoords

RealCoords 是一個 Geyser Extension，目標是在不改動 Java 世界真實座標的前提下，讓 Bedrock 客戶端在遠距離座標下仍使用較小、較穩定的本地座標。

> **目前狀態：架構重寫中，尚不可用於正式遊戲。**
>
> 目前分支正在由舊的 Bedrock 網路邊緣座標偏移方案，改為 Java downstream 封包層的座標原點重定位。舊方案可能讓 Geyser 內部座標狀態不一致，已在本分支停用。新的 Geyser 初始化掛鉤仍是提案／開發階段，尚未完成實作與整合測試。請勿把此分支建置的 JAR 部署到正式伺服器以期待座標轉換功能。

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
- [x] 建立 `CoordinateTransformTest` 測試案例（尚未在目前執行環境實際執行）。
- [x] 查證 Geyser `a973709` 的 downstream 建立生命週期。
- [x] 查證 MCProtocolLib 26.3 的 listener、Netty pipeline 與 `ConnectedEvent` 行為。
- [x] 確認 TCP 與 Geyser `LocalSession` 共用 MCProtocolLib channel initializer。
- [x] 撰寫 Geyser downstream 初始化事件提案與 patch 草稿。
- [x] 停用舊的 Bedrock-only 座標轉換入口，避免新舊座標架構同時作用。

## 正在處理

### Geyser 初始化掛鉤

Geyser 目前會在 `connectDownstream()` 建立 Java session，再依序註冊 MCProtocolLib `ClientListener` 與 Geyser `GeyserSessionAdapter`。

重要細節：`ClientListener.connected()` 會在 `ConnectedEvent` 中立即送出初始握手與登入封包。因此 RealCoords 的 session listener 必須在 `ClientListener` **之前**註冊，並在自己的 `connected()` callback 中先安裝 Netty duplex handler，才能攔截第一個 outbound 封包。

相關文件：

- [Geyser downstream hook proposal](docs/geyser-downstream-hook-proposal.md)
- [Geyser lifecycle investigation](docs/geyser-java-hook-investigation.md)
- [Geyser patch draft](patches/geyser/a973709-downstream-session-initialize.patch)

Patch 目前只存在 RealCoords repository，尚未套用到 Geyser fork，也尚未編譯或整合測試。

## 建置與測試

需要 JDK 21 與 Gradle。於 `RealCoords/` 專案目錄執行：

```bash
gradle --no-daemon clean build
```

目前尚未確認這個分支的完整 build 與測試結果。建置成功也不代表 downstream hook 或座標封包轉換已經通過整合測試。

在啟用任何座標轉換前，至少需要：

- Geyser API／核心 patch 能針對目標 commit 編譯。
- TCP 與 LocalSession 都能在第一個握手封包前安裝 handler。
- inbound 與 outbound packet 的攔截順序經測試確認。
- 連線轉移、重新連線與斷線清理正常。
- 逐類驗證所有絕對座標資料與 Geyser 內部狀態。
- 在獨立測試世界完成多人、遠距離、維度切換與互動測試。

## 分支安全約定

- 只在 `feature/coordinate-origin-rebasing` 開發。
- 不修改 `main`。
- 保留所有既有分支，不刪除分支。
- 未經測試不部署到正式伺服器。
