# RealCoords source module

此目錄包含 RealCoords 的 Gradle 專案原始碼。

完整專案介紹、浮動原點原理、安裝方式、設定說明、建置方式、Fork 指引、已知限制與待測清單，請參閱 repository 根目錄的 [README.md](../README.md)。

## 本機編譯

需要 JDK 21 與 Gradle 8.10.2：

~~~bash
gradle --no-daemon clean build
~~~

產物位於 build/libs/RealCoords-1.0.0.jar。
