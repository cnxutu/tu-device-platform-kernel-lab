package com.tu.deviceplatform.projectionreplay.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SrtGeneratorTest {
    @TempDir Path output;

    @Test
    void writesSegmentRelativeCuesAndLeavesLongGapBlank() throws Exception {
        GenerationConfig config = new GenerationConfig();
        config.droneSn = "DEMO-DRONE";
        config.originalVideoStartMs = 1_000_000;
        GenerationConfig.Segment first = segment("part-01", 0, 1000);
        GenerationConfig.Segment second = segment("part-02", 1000, 2000);
        config.segments = Arrays.asList(first, second);
        new SrtGenerator().generate(config, Arrays.asList(
                frame(1_000_100), frame(1_000_300), frame(1_001_100)), output);

        String firstSrt = new String(Files.readAllBytes(output.resolve("part-01.srt")), "UTF-8");
        String secondSrt = new String(Files.readAllBytes(output.resolve("part-02.srt")), "UTF-8");
        assertTrue(firstSrt.contains("00:00:00,100 --> 00:00:00,300"));
        assertTrue(firstSrt.contains("00:00:00,300 --> 00:00:00,700"));
        assertFalse(firstSrt.contains("00:00:00,700 -->"));
        assertTrue(secondSrt.contains("00:00:00,100 --> 00:00:00,500"));
        assertTrue(firstSrt.contains("\"reportTimeMs\":1000100"));
        JsonNode manifest = new ObjectMapper().readTree(output.resolve("manifest.json").toFile());
        assertEquals("part-01.mp4", manifest.at("/segments/0/videoUrl").asText());
        assertEquals("part-01.srt", manifest.at("/segments/0/telemetryUrl").asText());
        assertEquals(1_001_000, manifest.at("/segments/0/endTimeMs").asLong());
    }

    private GenerationConfig.Segment segment(String id, long start, long end) {
        GenerationConfig.Segment segment = new GenerationConfig.Segment();
        segment.id = id;
        segment.videoFile = id + ".mp4";
        segment.sourceStartMs = start;
        segment.sourceEndMs = end;
        segment.durationMs = end - start;
        return segment;
    }

    private OsdFrame frame(long time) {
        return new OsdFrame(time, "DEMO-DRONE", new BigDecimal("30.1"), new BigDecimal("120.2"),
                40f, 90f, -90d, 0d, 90d);
    }
}
