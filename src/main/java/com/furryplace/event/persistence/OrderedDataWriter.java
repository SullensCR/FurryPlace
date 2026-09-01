package com.furryplace.event.persistence;

import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class OrderedDataWriter implements AutoCloseable {
    private final Plugin plugin;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("furryplace-data-writer").factory());

    public OrderedDataWriter(Plugin plugin) {
        this.plugin = plugin;
    }

    public void submit(Path target, String contents) {
        executor.submit(() -> {
            try {
                AtomicFiles.writeString(target, contents);
            } catch (IOException exception) {
                plugin.getLogger().severe("No se pudo guardar " + target + ": " + exception.getMessage());
            }
        });
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                plugin.getLogger().severe("El guardado de datos no terminó antes del apagado.");
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}

