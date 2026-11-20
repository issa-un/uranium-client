package app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import net.minecraft.client.Minecraft;

/**
 * Appends dash and hotbar events to .minecraft/logs/uranium.log, so a dash that
 * did not switch back can be traced afterwards. Starts over when it passes 512 KB.
 */
final class UraniumLog {
    private static final long MAX_BYTES = 512 * 1024;

    private UraniumLog() {}

    static void log(String message) {
        Path file = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("logs").resolve("uranium.log");
        String line = LocalTime.now().truncatedTo(ChronoUnit.MILLIS) + "  " + message + System.lineSeparator();
        try {
            Files.createDirectories(file.getParent());
            boolean full = Files.exists(file) && Files.size(file) > MAX_BYTES;
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    full ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE);
        } catch (IOException ignored) {
            // Logging must never get in the way of the game.
        }
    }
}
