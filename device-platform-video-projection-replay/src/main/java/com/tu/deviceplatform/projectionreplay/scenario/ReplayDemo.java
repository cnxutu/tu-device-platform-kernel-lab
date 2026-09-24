package com.tu.deviceplatform.projectionreplay.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tu.deviceplatform.projectionreplay.adapter.SqliteOsdReader;
import com.tu.deviceplatform.projectionreplay.core.GenerationConfig;
import com.tu.deviceplatform.projectionreplay.core.OsdFrame;
import com.tu.deviceplatform.projectionreplay.core.SrtGenerator;

import java.nio.file.Paths;
import java.util.List;

/** Command-line entry for preparing video projection replay demo files. */
public final class ReplayDemo {
    private ReplayDemo() { }

    /** Usage: ReplayDemo <external-sqlite-path> <segments-json> <output-directory>. */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: ReplayDemo <external-sqlite-path> <segments-json> <output-directory>");
        }
        GenerationConfig config = new ObjectMapper()
                .readValue(Paths.get(args[1]).toFile(), GenerationConfig.class);
        long lastEnd = config.segments.stream().mapToLong(segment -> segment.sourceEndMs).max().getAsLong();
        List<OsdFrame> frames = new SqliteOsdReader().read(
                Paths.get(args[0]), config.droneSn, Math.addExact(config.originalVideoStartMs, lastEnd));
        System.out.println(new SrtGenerator().generate(config, frames, Paths.get(args[2])));
    }
}
