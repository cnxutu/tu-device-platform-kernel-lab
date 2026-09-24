# 本地 OSD 数据

`drc_osd.sqlite3` 是从原采集器复制的一致性快照，供本机 Demo 读取。它含真实设备轨迹和标识，已通过模块的 `.gitignore` 排除，不能提交或放入示例文件。

需要刷新时，从原采集器的 SQLite 数据库重新制作快照；不要复制采集进程仍在使用的 `-wal`、`-shm` 文件作为数据交付物。

`demo-osd.sql` 是可提交到 Git 的模拟样本，结构与采集器的 `drc_osd_samples` 一致，覆盖二维、三维和数据缺口。安装 SQLite 命令行后，可用 `sqlite3 data/demo-osd.sqlite3 ".read data/demo-osd.sql"` 创建独立测试库；生成的数据库同样被忽略。配合 `segments.example.json` 可运行不含真实设备数据的转换测试。
