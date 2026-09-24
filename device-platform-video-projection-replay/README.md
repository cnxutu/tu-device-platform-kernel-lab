# 历史视频投影回放 Demo

独立 Java 8 模块：读取 DJI DRC OSD SQLite，按 P1 geometryVersion=2 的视锥几何规则计算，按视频分片生成 SRT 和前端清单。本地 OSD 快照放在 `data/drc_osd.sqlite3`；数据库、视频和生成文件均被 Git 忽略，示例配置只含模拟值。

## 数据准备

1. 本机 OSD 快照已放在 `data/drc_osd.sqlite3`。其他机器需要自行从原采集器复制或重新采集；不要将数据库提交到 Git。
2. 将剪映导出的两段 MP4 放入 `frontend/public/demo/`。
3. 复制 `segments.example.json` 到仓库外，填写真实无人机 SN、原视频实际首帧 UTC 毫秒、每段在原视频中的精确裁剪区间、导出后视频的实际时长。示例配置中的时间和视场角不能直接用于真实投影。
4. 将视场角、偏航/横滚标定和模式阈值与现场 P1 配置核对。分片时间与媒体首帧需按画面动作校准 `syncOffsetMs`。

## 生成

在模块目录运行 `mvn test`；读取 SQLite 时启用 `sqlite` profile，然后用包含依赖的 Java classpath 运行：

```powershell
mvn -Psqlite dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
$cp = Get-Content target/classpath.txt
java -cp "target/classes;$cp" com.tu.deviceplatform.projectionreplay.scenario.ReplayDemo data/drc_osd.sqlite3 <真实分片配置JSON绝对路径> frontend/public/demo
```

生成结果：每段一个 `segment-xx.srt`，另有 `manifest.json`。SRT cue 使用片段内媒体毫秒；正文的 `timestamp` 和 `reportTimeMs` 保留原始 OSD UTC 毫秒。连续缺口超过 400ms 时不会无限延长旧几何。

## 前端预览

```powershell
cd frontend
npm install
npm run dev
```

页面从 `/demo/manifest.json` 读取成对的视频和 SRT。切换分片时清理上段投影，视频的 `currentTime` 是唯一回放时钟。正式对接 MinIO 时，清单的 URL 可换为受控对象地址；浏览器需能 fetch SRT，视频需允许用作 WebGL 纹理。

## 边界

算法从 P1 `DeviceViewConeGeometryCalculator` 提取为实验实现，保留同样的四角顺序与角度规则。P1 使用墙钟维持设备模式状态；这里按 OSD 上报时间维持离线状态。低于 -90° 的云台俯仰角、无效位置或不能形成四角的帧会被跳过。正式接入业务服务时，应改为直接复用 P1 的实时计算结果或共享几何实现。
