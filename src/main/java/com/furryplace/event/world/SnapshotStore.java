package com.furryplace.event.world;

import com.furryplace.event.persistence.AtomicFiles;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class SnapshotStore {
    private static final int MAGIC = 0x46504C43;
    private static final int FORMAT = 1;
    private final Path directory;

    public SnapshotStore(Path pluginDataFolder) {
        directory = pluginDataFolder.resolve("data").resolve("snapshots");
    }

    public Path path(long version) {
        return directory.resolve("template-" + version + ".bin.gz");
    }

    public void save(TemplateSnapshot snapshot) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(bytes))) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT);
            output.writeLong(snapshot.version());
            output.writeInt(snapshot.minimumY());
            output.writeInt(snapshot.maximumY());
            output.writeInt(snapshot.palette().size());
            for (String entry : snapshot.palette()) output.writeUTF(entry);
            output.writeInt(snapshot.runs().size());
            for (TemplateSnapshot.BlockRun run : snapshot.runs()) {
                output.writeInt(run.relativeX());
                output.writeInt(run.relativeZ());
                output.writeInt(run.fromY());
                output.writeInt(run.toY());
                output.writeInt(run.paletteIndex());
            }
        }
        AtomicFiles.writeBytes(path(snapshot.version()), bytes.toByteArray());
    }

    public TemplateSnapshot load(long version) throws IOException {
        byte[] bytes = Files.readAllBytes(path(version));
        try (DataInputStream input = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(bytes)))) {
            if (input.readInt() != MAGIC || input.readInt() != FORMAT) throw new IOException("Unsupported snapshot format");
            long storedVersion = input.readLong();
            int minimumY = input.readInt();
            int maximumY = input.readInt();
            int paletteSize = input.readInt();
            List<String> palette = new ArrayList<>(paletteSize);
            for (int index = 0; index < paletteSize; index++) palette.add(input.readUTF());
            int runCount = input.readInt();
            List<TemplateSnapshot.BlockRun> runs = new ArrayList<>(runCount);
            for (int index = 0; index < runCount; index++) {
                runs.add(new TemplateSnapshot.BlockRun(input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readInt()));
            }
            return new TemplateSnapshot(storedVersion, minimumY, maximumY, palette, runs);
        }
    }

    public boolean exists(long version) {
        return version > 0 && Files.isRegularFile(path(version));
    }

    public void deleteAll() throws IOException {
        if (!Files.isDirectory(directory)) return;
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().startsWith("template-")).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }
}

