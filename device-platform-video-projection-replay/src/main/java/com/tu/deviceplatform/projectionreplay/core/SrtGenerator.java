package com.tu.deviceplatform.projectionreplay.core;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Creates one telemetry SRT per video segment and a paired frontend manifest. */
public final class SrtGenerator {
    private static final long OUTPUT_INTERVAL_MS = 200;
    private static final long MAX_CUE_HOLD_MS = 400;
    private final ObjectMapper json = new ObjectMapper();

    /** Writes only inside outputDir; source media and OSD database are untouched. */
    public Map<String, Object> generate(GenerationConfig config, List<OsdFrame> input, Path outputDir)
            throws IOException {
        validate(config);
        Files.createDirectories(outputDir);
        List<OsdFrame> frames = new ArrayList<>(input);
        frames.sort(Comparator.comparingLong(frame -> frame.timestampMs));
        ProjectionCalculator calculator = new ProjectionCalculator(
                config.frameHfovDeg, config.frameVfovDeg, config.pitchTransitionDeg,
                config.pitchHysteresisDeg, config.yawOffsetDeg, config.rollOffsetDeg,
                config.rollDirection);
        List<ProjectedFrame> projected = new ArrayList<>();
        for (OsdFrame frame : frames) {
            Map<String, Object> payload = calculator.calculate(frame);
            if (payload != null) {
                projected.add(new ProjectedFrame(frame.timestampMs, payload));
            }
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("geometryVersion", 2);
        manifest.put("cornerOrder", "CAMERA_TL_TR_BR_BL");
        manifest.put("originalVideoStartMs", config.originalVideoStartMs);
        List<Map<String, Object>> entries = new ArrayList<>();
        manifest.put("segments", entries);
        for (GenerationConfig.Segment segment : config.segments) {
            long sourceStart = Math.addExact(config.originalVideoStartMs, segment.sourceStartMs);
            long sourceEnd = Math.addExact(config.originalVideoStartMs, segment.sourceEndMs);
            String srtName = segment.id + ".srt";
            int count = writeSegment(projected, segment, sourceStart, sourceEnd, outputDir.resolve(srtName));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("videoRecordId", segment.id);
            entry.put("videoUrl", segment.videoFile);
            entry.put("telemetryUrl", srtName);
            entry.put("telemetryStatus", count == 0 ? "NO_DATA" : "READY");
            entry.put("startTimeMs", sourceStart);
            entry.put("endTimeMs", sourceEnd);
            entry.put("durationMs", segment.durationMs);
            entry.put("syncOffsetMs", segment.syncOffsetMs);
            entry.put("cueCount", count);
            entries.add(entry);
        }
        json.writerWithDefaultPrettyPrinter().writeValue(outputDir.resolve("manifest.json").toFile(), manifest);
        return manifest;
    }

    private int writeSegment(List<ProjectedFrame> projected, GenerationConfig.Segment segment,
                             long sourceStart, long sourceEnd, Path srt) throws IOException {
        List<Cue> cues = new ArrayList<>();
        long lastStart = Long.MIN_VALUE;
        for (ProjectedFrame frame : projected) {
            if (frame.time < sourceStart || frame.time >= sourceEnd) {
                continue;
            }
            long start = Math.round((frame.time - sourceStart) * (double) segment.durationMs
                    / (sourceEnd - sourceStart));
            if (start >= segment.durationMs || lastStart != Long.MIN_VALUE
                    && start - lastStart < OUTPUT_INTERVAL_MS) {
                continue;
            }
            cues.add(new Cue(start, frame.payload));
            lastStart = start;
        }
        try (BufferedWriter writer = Files.newBufferedWriter(srt, StandardCharsets.UTF_8)) {
            for (int i = 0; i < cues.size(); i++) {
                Cue cue = cues.get(i);
                long next = i + 1 < cues.size() ? cues.get(i + 1).start : segment.durationMs;
                long end = Math.min(next, Math.min(cue.start + MAX_CUE_HOLD_MS, segment.durationMs));
                if (end <= cue.start) {
                    continue;
                }
                writer.write(Integer.toString(i + 1));
                writer.newLine();
                writer.write(srtTime(cue.start) + " --> " + srtTime(end));
                writer.newLine();
                writer.write(json.writeValueAsString(cue.payload));
                writer.newLine();
                writer.newLine();
            }
        }
        return cues.size();
    }

    /** Formats a nonnegative media offset as SubRip time. */
    public static String srtTime(long milliseconds) {
        long hours = milliseconds / 3_600_000;
        long minutes = milliseconds / 60_000 % 60;
        long seconds = milliseconds / 1_000 % 60;
        long millis = milliseconds % 1_000;
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d,%03d",
                hours, minutes, seconds, millis);
    }

    private static void validate(GenerationConfig config) {
        if (config.droneSn == null || config.droneSn.isEmpty()
                || config.originalVideoStartMs <= 0 || config.segments == null
                || config.segments.isEmpty()) {
            throw new IllegalArgumentException("droneSn, originalVideoStartMs and segments are required");
        }
        long previousEnd = -1;
        for (GenerationConfig.Segment segment : config.segments) {
            if (segment.id == null || !segment.id.matches("[A-Za-z0-9_-]+")
                    || segment.videoFile == null || segment.videoFile.isEmpty()
                    || segment.sourceStartMs < previousEnd || segment.sourceStartMs < 0
                    || segment.sourceEndMs <= segment.sourceStartMs || segment.durationMs <= 0) {
                throw new IllegalArgumentException("Invalid or overlapping video segment");
            }
            long sourceDuration = segment.sourceEndMs - segment.sourceStartMs;
            if (Math.abs(segment.durationMs - sourceDuration) > sourceDuration * 0.005) {
                throw new IllegalArgumentException("Exported duration differs by more than 0.5%: " + segment.id);
            }
            previousEnd = segment.sourceEndMs;
        }
    }

    private static final class ProjectedFrame {
        final long time;
        final Map<String, Object> payload;
        ProjectedFrame(long time, Map<String, Object> payload) {
            this.time = time;
            this.payload = payload;
        }
    }

    private static final class Cue {
        final long start;
        final Map<String, Object> payload;
        Cue(long start, Map<String, Object> payload) {
            this.start = start;
            this.payload = payload;
        }
    }
}
