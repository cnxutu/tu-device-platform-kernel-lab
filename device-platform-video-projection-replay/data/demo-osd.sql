-- Synthetic DJI DRC OSD rows for repeatable local tests. No real device data.
CREATE TABLE drc_osd_samples (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    received_at_ms INTEGER NOT NULL,
    message_timestamp_ms INTEGER,
    dock_sn TEXT NOT NULL,
    drone_sn TEXT NOT NULL,
    attitude_head REAL,
    latitude REAL,
    longitude REAL,
    height REAL,
    speed_x REAL,
    speed_y REAL,
    speed_z REAL,
    gimbal_pitch REAL,
    gimbal_roll REAL,
    gimbal_yaw REAL,
    raw_json TEXT NOT NULL
);

INSERT INTO drc_osd_samples (
    received_at_ms, message_timestamp_ms, dock_sn, drone_sn, attitude_head,
    latitude, longitude, height, speed_x, speed_y, speed_z,
    gimbal_pitch, gimbal_roll, gimbal_yaw, raw_json
) VALUES
    (1700000000105, 1700000000100, 'DEMO-DOCK', 'DEMO-DRONE', 90,
     30.1000, 120.2000, 40, 0, 0, 0, -90, 0, 90,
     '{"method":"osd_info_push","timestamp":1700000000100,"data":{"latitude":30.1000,"longitude":120.2000,"height":40,"attitude_head":90,"gimbal_pitch":-90,"gimbal_roll":0,"gimbal_yaw":90}}'),
    (1700000000305, 1700000000300, 'DEMO-DOCK', 'DEMO-DRONE', 90,
     30.1001, 120.2001, 40, 0, 0, 0, -60, 0, 90,
     '{"method":"osd_info_push","timestamp":1700000000300,"data":{"latitude":30.1001,"longitude":120.2001,"height":40,"attitude_head":90,"gimbal_pitch":-60,"gimbal_roll":0,"gimbal_yaw":90}}'),
    (1700000000505, 1700000000500, 'DEMO-DOCK', 'DEMO-DRONE', 90,
     30.1002, 120.2002, 40, 0, 0, 0, -58, 0, 90,
     '{"method":"osd_info_push","timestamp":1700000000500,"data":{"latitude":30.1002,"longitude":120.2002,"height":40,"attitude_head":90,"gimbal_pitch":-58,"gimbal_roll":0,"gimbal_yaw":90}}'),
    (1700000001105, 1700000001100, 'DEMO-DOCK', 'DEMO-DRONE', 90,
     30.1003, 120.2003, 40, 0, 0, 0, -45, 0, 90,
     '{"method":"osd_info_push","timestamp":1700000001100,"data":{"latitude":30.1003,"longitude":120.2003,"height":40,"attitude_head":90,"gimbal_pitch":-45,"gimbal_roll":0,"gimbal_yaw":90}}');
