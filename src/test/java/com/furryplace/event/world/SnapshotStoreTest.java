package com.furryplace.event.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotStoreTest {
    @Test
    void compressedSnapshotRoundTripsItsVersionPaletteAndRuns(@TempDir Path directory) throws Exception {
        TemplateSnapshot.Builder builder = new TemplateSnapshot.Builder(42L, -64, 319);
        builder.addRun(-2, -2, -64, 79, "minecraft:dirt");
        builder.addRun(0, 0, 80, 80, "minecraft:grass_block[snowy=false]");
        builder.addRun(0, 0, 81, 319, "minecraft:air");
        TemplateSnapshot expected = builder.build();
        SnapshotStore store = new SnapshotStore(directory);
        store.save(expected);
        assertTrue(store.exists(42L));
        TemplateSnapshot actual = store.load(42L);
        assertEquals(expected.version(), actual.version());
        assertEquals(expected.palette(), actual.palette());
        assertEquals(expected.runs(), actual.runs());
        assertEquals(expected.blockCount(), actual.blockCount());
        store.deleteAll();
        assertFalse(store.exists(42L));
    }
}
