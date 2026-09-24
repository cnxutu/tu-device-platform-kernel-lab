package com.tu.deviceplatform.projectionreplay.adapter;

import com.tu.deviceplatform.projectionreplay.core.OsdFrame;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Reads an external drc-osd-collector database without changing its contents. */
public final class SqliteOsdReader {

    /** Returns reports through the last video segment in event-time order. */
    public List<OsdFrame> read(Path database, String droneSn, long endExclusiveMs) throws SQLException {
        String url = "jdbc:sqlite:" + database.toAbsolutePath().toUri() + "?mode=ro";
        String sql = "SELECT message_timestamp_ms, drone_sn, latitude, longitude, height, "
                + "attitude_head, gimbal_pitch, gimbal_roll, gimbal_yaw "
                + "FROM drc_osd_samples WHERE drone_sn = ? AND message_timestamp_ms < ? "
                + "ORDER BY message_timestamp_ms, id";
        List<OsdFrame> frames = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, droneSn);
            statement.setLong(2, endExclusiveMs);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    frames.add(new OsdFrame(
                            row.getLong("message_timestamp_ms"),
                            row.getString("drone_sn"),
                            row.getBigDecimal("latitude"),
                            row.getBigDecimal("longitude"),
                            number(row, "height", Float.class),
                            number(row, "attitude_head", Float.class),
                            number(row, "gimbal_pitch", Double.class),
                            number(row, "gimbal_roll", Double.class),
                            number(row, "gimbal_yaw", Double.class)));
                }
            }
        }
        return frames;
    }

    private static <T extends Number> T number(ResultSet row, String column, Class<T> type) throws SQLException {
        Object value = row.getObject(column);
        if (value == null) {
            return null;
        }
        Number number = (Number) value;
        return type == Float.class ? type.cast(number.floatValue()) : type.cast(number.doubleValue());
    }
}
