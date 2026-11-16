# 海康 GEO 联调测试台

独立运行的本地测试页面：导入平台 `requestDTO`，生成或选择 GEO 点，预览海康设备 XML/curl，再按明确操作向平台发送请求。XML/curl 只作对照，不会由测试台直接调用设备。

## 构建与启动

要求 JDK 8+、Maven。模块的运行依赖只有 Jackson JSON；不依赖海康适配器制品、设备平台 SDK、Spring 或 Lombok，也不要求先构建其他业务仓库。首次构建仍需能取得公共 Maven 依赖。

从仓库根目录执行：

```powershell
mvn -pl device-platform-hikvision-geo-testbench -am clean verify
java -cp "device-platform-hikvision-geo-testbench/target/classes;device-platform-hikvision-geo-testbench/target/lib/*" com.tu.deviceplatform.hikvisiongeo.scenario.HikvisionGeoTestbenchMain
```

Linux/macOS 将 classpath 中的 `;` 改为 `:`。浏览器打开 `http://127.0.0.1:18092/`；main 可传端口参数覆盖默认值。依赖已缓存时 Maven 命令可加 `-o`。

环境变量 `HIKVISION_GEO_P2_BASE_URL` 或页面配置用于指定平台地址，`HIKVISION_GEO_P2_AUTHORIZATION` 用于需要鉴权的访问入口；凭据不回显、不落盘。启动本身不发送平台请求，使用模拟请求或确认过的联调输入后再明确下发。

## 本地 XML 映射的依据与边界

去依赖时间：2026-11-16。参考独立海康插件 `ad-iot-codec-adapter-hmtd6368100c4l`，Git 基线为 `release/1.0.2@da26cc15efbd8613ada81b7b7bee6035b38c3046`。以下路径均相对该插件仓库的 `src/main/java/com/xmkj/codec/adapter/uav/device/hikvision/`。

| 参考代码 | 测试台承接的位置与内容 |
| --- | --- |
| `mapper/HikvisionControlMapper.targetAcquire/targetFollow` | `core/HikvisionCommandPreview.create/gpsXml`：区分两种操作、跟踪开关和目标号 |
| `mapper/HikvisionControlMapper.gpsObjectInfo` | `gpsXml`：GPS 定位模式、方向、经纬度、高度结构 |
| `mapper/HikvisionControlMapper.coordinate` | `coordinate`：十进制度转度分秒，秒精确到 6 位，四舍五入及进位 |
| `dto/xml/PtzAbsoluteExXml` | `gpsXml/element`：根节点、版本、命名空间及子节点名称 |
| `constant/HikvisionIsapiConstants` | 预览路径 `/ISAPI/PTZCtrl/channels/{channel}/absoluteEx` 和 XML 命名空间 |

映射直接读取已校验的平台 JSON，用 JDK StAX `XMLStreamWriter` 写 XML。不再经过 SDK service DTO、适配器 mapper、XML DTO 和 Jackson XML codec 这几层；不新增 XML 库。

| 操作 | 设备 objectInfo.id | 连续跟踪 | 速度字段 |
| --- | --- | --- | --- |
| `target_acquire` | 1 | false | 水平／垂直均为 50 |
| `target_follow` | 123 | true | 不输出 |

速度 50/50 沿用参考代码的暂定默认值，未在本测试台做实机验证。平台 `targetId` 保持原 JSON，设备 `objectInfo.id` 使用上表固定值，两者不是同一身份。通道未指定时为 1，显式指定时只接受 1–9999 的正整数字符串。

本模块仅处理 GEO，原请求同时存在 PT 时仍使用 GEO；不承接插件的 PT-only 回退、设备状态查询、XML 反序列化、连接管理或其他控制业务。这里保存的是上述参考版本的映射规则，后续插件发生变化时需要显式核对，不会因依赖升级而自动变化。

## 验证方式

`HikvisionCommandPreviewTest` 检查两个操作的 XML 命名空间、默认速度、目标号、GEO 优先、东西南北方向、度分秒舍入进位、坐标边界、输入拒绝、通道路径和 curl，且确认预览不会修改平台 JSON。该组契约测试在移除旧依赖前先执行通过，再用于验证本地实现。

原有请求导入、选点、串行循环、停止和本地 HTTP 调用测试仍运行。测试使用模拟输入与本机假服务，不访问真实设备或平台；通过不代表设备已转到位、跟踪成功或平台端到端联调通过。

更新前曾构建过旧版本时，应使用 `clean verify`：Maven 的依赖复制不会自动删除旧的 `target/lib` JAR，clean 可防止旧适配器仍残留在命令行通配符类路径中。
