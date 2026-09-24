# 历史视频投影回放 Demo

独立 Java 8 模块：读取 DJI DRC OSD SQLite，按 P1 geometryVersion=2 的视锥几何规则计算，按视频分片生成 SRT 和前端清单。本地 OSD 快照放在 `data/drc_osd.sqlite3`；数据库、视频和生成文件均被 Git 忽略，示例配置只含模拟值。

## 数据准备

1. 本机 OSD 快照已放在 `data/drc_osd.sqlite3`。其他机器需要自行从原采集器复制或重新采集；不要将数据库提交到 Git。
2. 校准阶段使用 `data/drone-video-all.mp4`，并复制到 `frontend/public/demo/`。分片演示阶段再使用两段 MP4。
   本机校准配置是 `frontend/public/demo/segments.calibration.local.json`；按该配置生成的 `drone-video-all.srt` 与单视频 `manifest.json` 也在同一目录。以上真实素材均被 Git 忽略。
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

当前校准页面从 `/demo/manifest.json` 读取 `drone-video-all.mp4` 和 `drone-video-all.srt`。视频进度条和 OSD 滑条独立；找到对应画面后点击“将当前 OSD 对齐视频”，页面按 `OSD 时间 = 视频时间 + 偏移` 联动。2026-09-24 人工目视锚点的已保存偏移为 **−32,088ms**，已记录在本机忽略的 `segments.calibration.local.json` 和 `manifest.json`；这只是初步校准，约有 ±1 秒待微调。浏览器重新打开时默认用该偏移联动，若本机 localStorage 有更新值则优先采用更新值。偏移为负时，播放会从视频约 32.088 秒起进入 OSD 有效时间。OSD 滑条可浏览视频时长之外的数据；SRT 无 cue 的间隙明确显示无有效投影帧。完整 OSD 原始样本 2,766 条，中位上报间隔 100ms、首末跨度 307.078 秒；当前 Java SRT 生成器约 200ms 抽样，并跳过无效几何，因此校准 SRT 为 1,084 cue。完整视频时长 300.492 秒，两条时间线不能直接按总时长视为已对齐。

默认详细底图使用在线 OpenStreetMap 瓦片，因此需要网络；断网时仍保留 Cesium 自带的低精度 Natural Earth 底图。如有本地地图瓦片服务，可在 `frontend/.env.local` 配置 `VITE_MAP_TILE_URL=http://127.0.0.1:<端口>/tiles/{z}/{x}/{y}.png`，以本地加载详细底图；需重启 Vite。正式对接 MinIO 时，清单的 URL 可换为受控对象地址；浏览器需能 fetch SRT，视频需允许用作 WebGL 纹理。

## 边界

算法从 P1 `DeviceViewConeGeometryCalculator` 提取为实验实现，保留同样的四角顺序与角度规则。P1 使用墙钟维持设备模式状态；这里按 OSD 上报时间维持离线状态。低于 -90° 的云台俯仰角、无效位置或不能形成四角的帧会被跳过。正式接入业务服务时，应改为直接复用 P1 的实时计算结果或共享几何实现。
