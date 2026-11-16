# Netty 与适配器并发实验

建立时间：2026-11-16。对应 `ad-iot-codec-adapter-nnq52xt75` 的提交 `50f1240d3ad7be58e3045b2e290a9c3fecef21fe`。

本模块将适配器中的六种机制变成可单独运行、打断点和验证的小实验。重点是线程、任务、状态所有权与关闭顺序，不复制设备业务。阅读与断点操作见[IDEA 调试指南](docs/debugging-guide.md)。

## 学习入口

说明补强时间：2026-11-16。建议从[组件、概念与用法](docs/concepts-and-usage.md)开始，分清 Channel、EventLoop、worker、Future、定时任务与锁，再选一个 Demo 调试。

`ConcurrencyScenariosTest` 的类注释提供分类导航，每项测试都说明准备条件、验证证据与结论边界。IDEA JUnit 测试树通过 `@DisplayName` 显示“技术大类｜分类主题｜验证点”：

- **Netty**：线程模型、回调执行、定时调度。
- **Java 并发**：生命周期互斥，区分 volatile 与 synchronized。
- **Netty 与 Java 并发**：连接代次、异步资源关闭。

每个 Demo 的类注释说明问题、预期和断点；关键语句旁解释切线程、等待、取消或身份检查的原因。公共组件的注释交代职责、线程归属、调用条件与返回信号含义。源码适合边看边调，概念文档用于查组件，调试指南用于沿时序逐步观察。

## 运行方式

要求 JDK 8+、Maven 3.8+。只有 Netty 4.1.116.Final 和 JUnit 5 依赖，不需要启动网关或访问私服业务 SDK。首次构建需能取得 Maven 依赖；已有依赖时可加 `-o` 离线构建。

从仓库根目录执行，只构建本模块及父工程：

```powershell
mvn -pl device-platform-netty-concurrency-lab -am clean verify
```

IDEA 导入根 POM 后，每个 Demo 类的 `main` 均可直接 Run 或 Debug。命令行运行示例（Windows，仓库根目录）：

```powershell
java -Dfile.encoding=UTF-8 -cp "device-platform-netty-concurrency-lab/target/classes;device-platform-netty-concurrency-lab/target/lib/*" com.tu.deviceplatform.nettylab.scenario.SharedEventLoopDemo
```

Linux/macOS 将 classpath 中的 `;` 改成 `:`。替换末尾类名即可运行其他五个实验。`target/lib` 由 `verify` 包含的 package 阶段准备，不要求先 install 本模块。

### IDEA 提示无法解析 JUnit 启动器

若点击测试方法后提示 `Failed to resolve org.junit.platform:junit-platform-launcher:1.10.2`，说明测试启动依赖解析失败，尚未执行实验断言。Maven Surefire 与 IDEA 直接运行测试的启动路径不同，因此 Maven 测试通过时，IDEA 仍可能在补充下载 launcher 时受仓库或代理影响。

本模块已显式声明 test 范围的 `junit-platform-launcher:1.10.2`，与 Jupiter 5.10.2 配套。修改 POM 后在 IDEA Maven 窗口执行 **Reload All Maven Projects**，再运行测试；该依赖不进入 Demo 的运行库。

如果 Maven 重载也无法取得依赖，检查 IDEA Maven 使用的 settings.xml／本地仓库，以及 IDEA HTTP Proxy 设置。2026-11-16 的一次本机故障日志为 `Connect to 127.0.0.1:7892 failed: Connection refused`，表示当时配置的代理未能连接；应根据当前网络情况修正或停用该代理，不能将它当成 Netty 或测试断言失败。

## 六个入口

| 建议顺序 | main 类（均在 `com.tu.deviceplatform.nettylab.scenario`） | 实际演示 |
| --- | --- | --- |
| 1 | `SharedEventLoopDemo` | 三个独立 TCP Channel 共用同一客户端线程，消息各归各设备 |
| 2 | `CallbackOffloadDemo` | 慢回调在 I/O 时阻塞心跳；卸载后心跳继续，但共享 worker 上其他回调仍等待 |
| 3 | `ScheduledSequenceDemo` | 受理、延迟 AI、执行完成分别可见；等待时 PING 往返，STOP 取消旧 AI |
| 4 | `LifecycleLockDemo` | 一次 close 仍与设备登记交错；对比 volatile 单独使用与同一实例锁互斥 |
| 5 | `StaleCallbackDemo` | 旧 Channel 回调排队时换成新连接，对比是否检查连接身份 |
| 6 | `GracefulCloseDemo` | 普通线程等待关闭完成；worker 内只发起关闭，随后连接与执行器退出 |

运行自动结束，关键断言失败时进程以异常结束。默认事件等待上限 10 秒，取消前的延迟任务为 3000ms；实际演示通常很快结束，不会人为等待所有超时。

断点调试可设置 VM options：

```text
-Dfile.encoding=UTF-8 -Dlab.waitSeconds=300 -Dlab.delayMillis=60000
```

`lab.waitSeconds` 只放宽实验的事件和清理等待；`lab.delayMillis` 放宽待取消 AI／模拟重连的窗口，不是生产配置。正常自动化验证使用默认值。长时间暂停仍可能改变定时顺序，具体断点方式见指南。

## 日志与代码组织

日志示例（步骤号和 Channel ID 每次不同）：

```text
001 [main] [device-1] 已建连 channel=...
002 [adapter-io-4-1] [device-1] 收到 device-1 channel=...
...
[main] [结论] 三个客户端 Channel 共享 adapter-io-...；sim-* 为独立模拟设备端线程
[main] [资源] TCP 与客户端／模拟设备端执行器均已退出
```

- `scenario/`：独立入口和实验断言；JUnit 测试直接运行同一 `run()`。
- `adapter/LocalTcpLab`：仅监听 `127.0.0.1`、动态端口的回显设备，以及共享一条 I/O／worker 的客户端。使用换行分帧，不实现 TJSON。
- `core/TimedSequence`：最小有限序列，状态由 EventLoop 独占。
- `core/LifecycleRegistry`：稳定安排锁实验交错的假资源登记表；错误分支也清理资源。
- `core/ClosingRuntime`：关闭流程的单设备模型，模拟待重连任务，不实现生产重连策略。
- `core/LabSupport`：步骤日志、有界等待和断言，不是通用实验框架。

客户端的 `adapter-io-*`、`adapter-worker-*` 与模拟设备端的 `sim-*` 不同；模拟设备端还有接入线程。因此“客户端共享一条 I/O”不表示整个进程只有一条线程，也不包括 Netty 全局辅助线程。

## 证据与范围

六个测试分别验证共享线程与身份隔离、慢回调期间心跳进展、延迟任务取消、两种锁交错、旧回调过滤和完整关闭。每个 TCP 实验退出时检查 Channel 与本实验持有的执行器已退出。

2026-11-16 本地执行 `mvn -o -B -ntp -pl device-platform-netty-concurrency-lab -am clean verify`：6 项测试全部通过，无失败、错误或跳过；六个 main 也分别以独立 JVM 启动并正常退出，退出码均为 0。日志保存在未提交的 `target/<Demo类名>.log`，重建可删除。审查覆盖线程所有权、锁的保护范围、定时取消、旧回调身份及退出路径，本轮范围未发现阻断项；未构建其他实验模块，未验证 IDEA 界面操作或真实平台链路。

生产源码映射、调试步骤及差异集中在[调试指南](docs/debugging-guide.md)。实验只证明教学模型中的受控路径，不证明生产协议、完整 Gateway、插件隔离加载或实机行为；未把旧聚合适配器客户端改成当前插件的替代品。
