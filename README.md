# RealCoords

此 Repository 已設定 GitHub Actions 自動編譯。

## 加入原始碼（必要）

請將你最初提供的 Claude 專案壓縮檔上傳到 Repository 根目錄，檔名必須是：

`RealCoords-source.zip`

ZIP 內的目錄結構需包含 `RealCoords/build.gradle.kts` 和 `RealCoords/src/`。

## 編譯與下載

上傳後開啟 **Actions → Build RealCoords**。成功時，在該次工作流程頁面下方的 **Artifacts** 下載 `RealCoords-JAR`。

建置環境：Java 21、Gradle 8.10.2、Geyser 2.12.1-SNAPSHOT。

若依賴版本不存在或程式碼與 Geyser API 不相容，Actions 會顯示錯誤。編譯成功也仍需在測試伺服器實際驗證。
