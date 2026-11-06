# Java 協議層 Hook 調查（Geyser 2.12.1）

調查日期：2026-11-06  
專案建置目標：`Geyser 2.12.1-SNAPSHOT`（`RealCoords/gradle.properties`）  
範圍：確認能否在 Geyser 將 Java 封包交給 translator 前，以 Netty handler 替換封包實例。

## 結論

**Java Netty pipeline 可以攔截並替換兩個方向的 MCProtocolLib `Packet`，技術路徑可行。**  
不應使用 `SessionListener.packetReceived` 作為封包改寫點；它是通知回呼，沒有回傳／替換封包的介面。應在解碼後、MCProtocolLib 的 `manager` handler 前插入 `ChannelDuplexHandler`。

但有一個重要生命週期限制：**`SessionLoginEvent` 發生時，Java downstream session 尚未建立**。因此不能在目前的 `onLogin` 回呼中立即讀取 channel 並安裝 handler。後續實作必須先取得建立後的 downstream session，再於其 `ConnectedEvent` 時安裝（若已連線則在 event loop 上立即安裝），並以首包日誌驗證安裝時序。若無法保證在第一個遊戲封包前安裝，必須 fail closed，不可默默啟用座標轉換。

## 1. GeyserSession / DownstreamSession / Session 實作

Geyser 的 `GeyserSession` 保存欄位：

- `GeyserSession.downstream` 型別是 `DownstreamSession`。
- `DownstreamSession` 是包裝類，保存 `ClientSession session`；其 Lombok `@Getter` 會提供 `getSession()`。
- 因此 channel 的存取路徑是 `geyserSession.getDownstream().getSession().getChannel()`，但只有底層 session 完成連線、channel 已初始化後才能呼叫。

Geyser 的 `connectDownstream()` 會依平台選擇底層 session：

- 若 `geyser.getBootstrap().getSocketAddress() != null`（Velocity 內建／本機通道路徑），建立 `org.geysermc.geyser.network.java.LocalSession`。
- 否則建立 `org.geysermc.mcprotocollib.network.session.ClientNetworkSession`。

**Velocity 路徑不是 `TcpSession`。** Geyser 的 `LocalSession` extends `ClientNetworkSession`；MCProtocolLib 的 `ClientNetworkSession` extends `NetworkSession`。兩者都沿用 MCProtocolLib 的 Netty pipeline 與 `NetworkSession.getChannel()`。因此不應將 hook 寫死成 TCP channel 或 cast 成 `TcpSession`。

## 2. Pipeline 與封包處理順序

MCProtocolLib 的 `MinecraftChannelInitializer#addHandlers` 依序加入：

1. `read-timeout`
2. `write-timeout`
3. `encryption`
4. `sizer`
5. `compression`
6. `flow-control`
7. `codec`（`PacketCodec`）
8. `flush-handler`
9. `manager`（底層 `NetworkSession`）

建議位置：`pipeline.addBefore(NetworkConstants.MANAGER_NAME, "realcoords-java", handler)`。

此位置的效果：

- inbound：`PacketCodec` 已解碼成 MCProtocolLib `Packet`，handler 的 `channelRead` 可檢查並以新實例替換，再交給 `manager`。
- outbound：從 `manager` 往前傳遞，handler 的 `write` 可檢查並替換即將送入 `PacketCodec` 的 `Packet`。
- 非目標封包原樣傳遞。

這正是需要的「decode 之後、MCPL manager 之前」位置。應使用 `ChannelDuplexHandler`，不要攔截已轉成 Bedrock 封包的 upstream channel。

## 3. SessionListener 是否能替換封包

不能把 `SessionListener.packetReceived(Session, Packet)` 當成改寫 API：它是 void callback，呼叫端已持有原封包，listener 無法回傳替代實例。

MCProtocolLib 的 outbound `PacketSendingEvent` 有可替換的 packet 欄位，但它只涵蓋 outbound，不能解決 inbound 的替換需求。為了讓兩個方向都在同一個明確位置轉換，仍採 Netty pipeline handler。

## 4. 安裝時機：SessionLoginEvent 不夠早也不夠完整

Geyser 的 `GeyserSession#connectDownstream()` 執行順序是：

1. 建立並 fire `SessionLoginEvent`。
2. 處理事件結果與 remote server。
3. 建立 `LocalSession` 或 `ClientNetworkSession`。
4. 包成 `DownstreamSession`。
5. 註冊 MCProtocolLib `ClientListener` 與 Geyser 的 `GeyserSessionAdapter`。
6. 呼叫 `downstream.connect(false)`。

所以在 `SessionLoginEvent` callback 內，`getDownstream()` 尚未建立；直接安裝會失敗。此事件可作為「開始追蹤該玩家 downstream」的起點，不能視為 channel 已存在。

MCProtocolLib 的 `NetworkSession#channelActive` 在 channel 啟用時先保存 channel，接著 fire `ConnectedEvent`，最後 flush pending queue。建議在 downstream 建立後註冊一個 `SessionListener`，於 `connected()` 回呼內、同一 channel event loop 上安裝 handler。若註冊時 session 已 connected，則直接在 channel event loop 安裝。這比等待 `SessionJoinEvent` 安全；目前 Geyser 2.12.1-SNAPSHOT 的 `GeyserSession` 連線程式碼沒有使用 `SessionJoinEvent` 作為 downstream channel lifecycle hook。

**時序驗收條件：** 安裝時輸出 pipeline names；handler 分別對第一個 inbound 與 outbound MCProtocolLib packet 輸出一行日誌。若第一個遊戲封包已先抵達 Geyser adapter，或無法取得 manager 前的 handler 位置，必須停用 Java layer 並記錄錯誤，不能把未轉換封包交給 Geyser。

## 5. 除錯輸出要求

Java hook 安裝後應輸出：

- 玩家識別資訊（避免記錄敏感 token／驗證資料）。
- pipeline names。
- 第一次攔到的 inbound packet class。
- 第一次攔到的 outbound packet class。
- 安裝失敗、channel 尚未建立、manager handler 不存在等錯誤。

首包日誌應以每個玩家／連線各一次為限，避免每個 tick 洗版。

## 6. 本次調查涉及的原始碼

專案內：
- `RealCoords/gradle.properties`：Geyser 依賴版本。
- `RealCoords/src/main/java/dev/realcoords/RealCoordsExtension.java`：目前在 `SessionLoginEvent` 呼叫舊 Bedrock hook。
- `RealCoords/src/main/java/dev/realcoords/SessionHook.java`：目前 hook 是 Bedrock channel，不能挪作 Java hook。

上游：
- Geyser `GeyserSession`：`core/src/main/java/org/geysermc/geyser/session/GeyserSession.java`
- Geyser `DownstreamSession`：`core/src/main/java/org/geysermc/geyser/session/DownstreamSession.java`
- Geyser `LocalSession`：`core/src/main/java/org/geysermc/geyser/network/java/LocalSession.java`
- MCProtocolLib `ClientNetworkSession`：`protocol/src/main/java/org/geysermc/mcprotocollib/network/session/ClientNetworkSession.java`
- MCProtocolLib `NetworkSession`：`protocol/src/main/java/org/geysermc/mcprotocollib/network/session/NetworkSession.java`
- MCProtocolLib `MinecraftChannelInitializer`：`protocol/src/main/java/org/geysermc/mcprotocollib/network/netty/MinecraftChannelInitializer.java`
- MCProtocolLib `NetworkConstants`：`protocol/src/main/java/org/geysermc/mcprotocollib/network/NetworkConstants.java`

> 版本註記：本 repo 以 Maven snapshot `2.12.1-SNAPSHOT` 編譯；上游原始碼結構依 Geyser／MCProtocolLib 對應 source tree 檢查。最終 API 相容性仍以本 repo 的 Gradle compile/test 結果為準，不宣稱已做 Velocity 實機測試。
