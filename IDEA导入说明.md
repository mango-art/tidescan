# IntelliJ IDEA 导入和运行

1. 解压压缩包。在 IntelliJ IDEA 中选择 **File → Open**，打开解压后的 `tidescan` 目录（或选择其中的 `pom.xml`），按提示作为 Maven 项目导入。
2. 将 Project SDK 和 Maven 的 JDK 设置为 **JDK 21**，等待 Maven 下载或加载依赖。此电脑已有的 Maven 本地仓库为 `F:\tidescan\maven-repository`，可在 IDEA 的 Maven 设置中选用；其他电脑可使用自己的仓库。
3. 在项目中运行 `src/main/java/cn/tidescan/TideScanApplication.java` 的 `main` 方法，浏览器打开 `http://127.0.0.1:8080/`。也可以先运行 Maven `package`，再使用 `start-tidescan.bat`。
4. 日线和股本数据位于项目 `data/market.db`，已有分时缓存位于 `cache/minute`。工作目录应设为项目根目录，程序将从这里读取和更新数据。快照包含打包时已有的分时缓存；其他分时仍需接口读取。账户数据不包含在包内，首次运行会在项目内创建新的模拟账户。
5. 麦瑞许可证放在项目根目录的 `data/secrets.properties`，属性名为 `mairui.daily-licence` 和 `mairui.minute-licence`（格式见 README）。该文件没有放入压缩包，也不纳入版本管理；其他电脑需自行创建。文件缺失时程序仍能启动，只是无法同步行情。

如果原服务已经占用 8080，先停止原服务再运行，或者在 IDEA 运行配置的 Program arguments 中指定其他端口，例如 `--server.port=8082`。同一份本地数据不要同时由多个实例写入；使用其他端口时应同时配置独立的 `--tidescan.root`，避免与现有服务竞争。

项目采用标准 Maven 目录布局，Java 测试位于 `src/test`。`tests` 中的浏览器辅助脚本使用本机特定的 Playwright、Chrome 路径及测试端口，需要先按运行环境调整；它们不是 IDEA 导入或启动项目的前提。部分浏览器测试会重置测试账户，只能对使用独立测试数据的服务运行。更多功能说明和数据限制见 `README.md`。
