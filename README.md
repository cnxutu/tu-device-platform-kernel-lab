# tu-device-platform-kernel-lab

面向复杂设备平台场景的微内核实验仓库。目标不是复刻生产系统，而是把物模型、协议适配、消息路由和后续的视频平台集成拆成可单独运行、可验证的最小模型。

## 模块

| 模块 | 责任 |
| --- | --- |
| `device-platform-thing-model` | 物模型能力定义、消息标准化、内核校验、协议适配与场景模拟 |

## 构建与运行

要求 JDK 8+、Maven 3.8+。

```powershell
mvn test
java -cp device-platform-thing-model/target/classes com.tu.deviceplatform.thingmodel.scenario.ThingModelDemo
```

先执行 `mvn test`，再运行示例。示例包含两组模拟数据：上行 OSD 属性上报和下行服务调用。

## 演进方式

后续新增能力时，优先新增独立 Maven 子模块；例如视频流平台、设备状态投影或消息可靠性，都应通过明确的模块依赖接入，而不是堆叠进物模型内核。

