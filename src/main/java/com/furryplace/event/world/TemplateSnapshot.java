package com.furryplace.event.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TemplateSnapshot {
    public record BlockRun(int relativeX, int relativeZ, int fromY, int toY, int paletteIndex) {
        public BlockRun {
            if (toY < fromY || paletteIndex < 0) throw new IllegalArgumentException("Invalid block run");
        }

        public int blockCount() {
            return toY - fromY + 1;
        }
    }

    public static final class Builder {
        private final long version;
        private final int minimumY;
        private final int maximumY;
        private final List<String> palette = new ArrayList<>();
        private final Map<String, Integer> paletteIndexes = new LinkedHashMap<>();
        private final List<BlockRun> runs = new ArrayList<>();

        public Builder(long version, int minimumY, int maximumY) {
            this.version = version;
            this.minimumY = minimumY;
            this.maximumY = maximumY;
        }

        public void addRun(int relativeX, int relativeZ, int fromY, int toY, String blockData) {
            int paletteIndex = paletteIndexes.computeIfAbsent(blockData, value -> {
                palette.add(value);
                return palette.size() - 1;
            });
            runs.add(new BlockRun(relativeX, relativeZ, fromY, toY, paletteIndex));
        }

        public TemplateSnapshot build() {
            return new TemplateSnapshot(version, minimumY, maximumY, palette, runs);
        }
    }

    private final long version;
    private final int minimumY;
    private final int maximumY;
    private final List<String> palette;
    private final List<BlockRun> runs;
    private final long blockCount;

    public TemplateSnapshot(long version, int minimumY, int maximumY, List<String> palette, List<BlockRun> runs) {
        this.version = version;
        this.minimumY = minimumY;
        this.maximumY = maximumY;
        this.palette = List.copyOf(palette);
        this.runs = List.copyOf(runs);
        this.blockCount = runs.stream().mapToLong(BlockRun::blockCount).sum();
    }

    public long version() { return version; }
    public int minimumY() { return minimumY; }
    public int maximumY() { return maximumY; }
    public List<String> palette() { return palette; }
    public List<BlockRun> runs() { return runs; }
    public long blockCount() { return blockCount; }
}

