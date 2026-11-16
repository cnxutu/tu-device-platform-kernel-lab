# tu-device-platform-kernel-lab

面向复杂设备平台场景的微内核实验仓库。目标不是复刻生产系统，而是把物模型、协议适配、消息路由和后续的视频平台集成拆成可单独运行、可验证的最小模型。

## 模块

| 模块 | 责任 |
| --- | --- |
| `device-platform-netty-concurrency-lab` | 六个可独立调试的 Netty／并发实验：共享 EventLoop、回调卸载、定时序列、生命周期锁、旧回调隔离和完整关闭；见[运行说明](device-platform-netty-concurrency-lab/README.md)与[调试指南](device-platform-netty-concurrency-lab/docs/debugging-guide.md) |
| `device-platform-thing-model` | 物模型能力定义、消息标准化、内核校验、协议适配与场景模拟 |
| `device-platform-video-projection-replay` | 从 DJI DRC OSD 生成分片 SRT，并提供历史视频投影回放前端 Demo |
| `device-platform-tofu-ms-simulator` | TOFU-MS 设备 TCP 模拟器，提供独立报文接收、心跳、属性与 ACK |
| `device-platform-tofu-ms-command-client` | 光电控制调用、运动计时与 STOP |
| `device-platform-tofu-ms-gateway-web` | 物模型控制页面、模拟平台 RPC、embedded/remote 模式与独立网关链路验证 |
| `device-platform-hikvision-geo-testbench` | 海康 GEO 请求导入、指定/随机单发、地图八方位选点、圆内随机序列、P2 下发与设备 XML/curl 对照 |

## 构建与运行

要求 JDK 8+、Maven 3.8+。

```powershell
mvn test
java -cp device-platform-thing-model/target/classes com.tu.deviceplatform.thingmodel.scenario.ThingModelDemo
```

先执行 `mvn test`，再运行示例。示例包含两组模拟数据：上行 OSD 属性上报和下行服务调用。

## 演进方式

TOFU-MS 的职责、适配器与编解码、TCP 数据流、配置同步以及新设备接入最小闭环，详见本仓库的 [TOFU-MS 新设备接入网关案例](D:/workspace/github/tu-device-platform-kernel-lab/docs/tofu-ms-gateway-integration-case.md)。文章包含代码依赖图、运行流程图、启动与控制时序图、具体报文字节转换及可复现的测试步骤。

第二阶段的真实产品录入、平台与侦测业务集成设计，见 [TOFU-MS 实验链路与完整平台集成对照](docs/tofu-ms-platform-integration-comparison.md)。包含两种链路的 Mermaid 图、产品身份关联流程、控制反馈时序、模块职责与待补齐差异；正式链路尚待实施和验收。

当前独立网关链路的汇总与验收说明维护在 Workbench 的 [TOFU-MS 独立网关控制链路](../tu-engineering-workbench/products/company/device-detection-platform/flows/tofu-ms-gateway-chain.md)。可执行的构建、启动、验证与停止脚本保留在本仓库 `scripts/`。

海康 GEO 测试台在模块内实现 GEO XML 预览映射，不依赖海康适配器或业务 SDK；规则来源及使用说明见[模块文档](device-platform-hikvision-geo-testbench/README.md)。通过 `mvn -pl device-platform-hikvision-geo-testbench -am package` 构建后，可用 `java -cp "device-platform-hikvision-geo-testbench/target/classes;device-platform-hikvision-geo-testbench/target/lib/*" com.tu.deviceplatform.hikvisiongeo.scenario.HikvisionGeoTestbenchMain` 启动页面，默认监听 `http://127.0.0.1:18092/`。P2 地址默认是 `http://192.168.1.123:48084`，也可在页面修改或通过 `HIKVISION_GEO_P2_BASE_URL` 覆盖。当前 P2 `/rpc-api/iot/**` 路由允许匿名访问，Authorization 无需配置；如经其他需要鉴权的代理访问，可在启动进程前设置 `HIKVISION_GEO_P2_AUTHORIZATION`，值只驻留进程内存且不会回显。导入的 P1-1 `requestDTO` 只驻留内存。单点模式提供指定点下发、圆内随机点下发和东南西北轴线随机选点；两种随机点均距中心严格小于设置半径并保留 1 cm 边界余量，方位点只更新预览坐标，需手动点击“指定点下发”才会发送到 P2。地图八方位点击也只更新坐标，需手动下发。四方位序列和最多 10 分钟的圆内随机连续模式都必须显式启动；循环按串行请求运行，P2 失败或页面心跳超时会停止后续下发。设备 XML/curl 只生成预览，不会直接请求设备。

后续新增能力时，优先新增独立 Maven 子模块；例如视频流平台、设备状态投影或消息可靠性，都应通过明确的模块依赖接入，而不是堆叠进物模型内核。
