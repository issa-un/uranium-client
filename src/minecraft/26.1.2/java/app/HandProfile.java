package app;

import java.util.Locale;
import java.util.Properties;

/**
 * How your hand moves a mouse, from the mouse test: the shape of a flick only, never how
 * fast or how accurate you are. The modules' aim (HumanAim) moves the way your hand does
 * (where a flick stops before its correction, how often it goes past, where its speed
 * peaks, how much it bends), at the game's own human pace. The defaults are an average
 * hand's, from the Mouse Lab recording. Every value is held to a range real hands fall in,
 * so a rushed test can't make the aim look robotic or wild.
 */
final class HandProfile {
    // Where the main push stops, as a share of the way, when it stops short.
    private static volatile double reach = 0.91;
    private static volatile double reachSpread = 0.05;
    // How often the main push goes past the target.
    private static volatile double overshoot = 0.27;
    // Where in the push the speed peaks (0.5: the middle), and how much that varies.
    private static volatile double peak = 0.46;
    private static volatile double peakSpread = 0.12;
    // How much the path bows to a side, as a share of the distance.
    private static volatile double bend = 0.05;
    // Whether the test was taken (or skipped): the first menu opening asks once.
    private static volatile boolean asked;
    private static volatile boolean measured;

    private HandProfile() {}

    static double reach() {
        return reach;
    }

    static double reachSpread() {
        return reachSpread;
    }

    static double overshoot() {
        return overshoot;
    }

    static double peak() {
        return peak;
    }

    static double peakSpread() {
        return peakSpread;
    }

    static double bend() {
        return bend;
    }

    static boolean isAsked() {
        return asked;
    }

    static boolean isMeasured() {
        return measured;
    }

    static void markAsked() {
        asked = true;
    }

    /** Takes the test's numbers, each held to a range real hands fall in. */
    static void set(double reachValue, double reachSpreadValue, double overshootValue, double peakValue,
            double peakSpreadValue, double bendValue) {
        reach = clamp(reachValue, 0.78, 0.97);
        reachSpread = clamp(reachSpreadValue, 0.02, 0.09);
        overshoot = clamp(overshootValue, 0.10, 0.45);
        peak = clamp(peakValue, 0.32, 0.62);
        peakSpread = clamp(peakSpreadValue, 0.05, 0.18);
        bend = clamp(bendValue, 0.02, 0.09);
        measured = true;
        asked = true;
    }

    /** Back to the average hand (humans.txt goes too). */
    static void reset() {
        try {
            java.nio.file.Files.deleteIfExists(humansFile());
        } catch (Exception ignored) {
            // nothing to remove
        }
        reach = 0.91;
        reachSpread = 0.05;
        overshoot = 0.27;
        peak = 0.46;
        peakSpread = 0.12;
        bend = 0.05;
        measured = false;
    }

    /** One line in plain words. */
    static String describe() {
        return String.format(Locale.ROOT, "stops %.0f%% of the way, goes past %.0f%% of the time, fastest %.0f%% in, bends %.1f%%",
                reach * 100, overshoot * 100, peak * 100, bend * 100);
    }

    /**
     * humans.txt, next to Uranium.bat in the Uranium folder (where the injector runs from):
     * your hand, in plain words and numbers you can read, keep, or share. It wins over the
     * copy in the settings file; delete it to go back to an average hand. Found from the
     * jar the game loaded (Uranium\build\libs\...jar, up to the folder with Uranium.bat);
     * the .minecraft config folder if that can't be found.
     */
    static java.nio.file.Path humansFile() {
        try {
            java.nio.file.Path at = java.nio.file.Path.of(HandProfile.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
            for (java.nio.file.Path dir = at.getParent(); dir != null; dir = dir.getParent()) {
                if (java.nio.file.Files.isRegularFile(dir.resolve("Uranium.bat"))) {
                    return dir.resolve("humans.txt");
                }
            }
        } catch (Exception ignored) {
            // no jar location: the config folder below
        }
        return net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("humans.txt");
    }

    /** Writes humans.txt (only once the test was taken). */
    static void saveHumans() {
        if (!measured) {
            return;
        }
        String text = String.format(Locale.ROOT, """
                # humans.txt: how your hand moves a mouse, from Uranium's mouse test.
                # Only the shape of a move, never your speed or aim. Uranium's aim moves like this.
                # Delete this file to go back to an average hand; take the test again from Play Style.
                #
                # Your hand: %s
                measured=true
                # where your main push stops before you correct (share of the way)
                stops_at=%.3f
                # how much that changes from move to move
                stops_spread=%.3f
                # how often your push goes past the target
                goes_past=%.3f
                # where in the push you're fastest (0.5 = the middle)
                fastest_at=%.3f
                # how much that changes from move to move
                fastest_spread=%.3f
                # how much the path bends to a side (share of the distance)
                bends=%.3f
                """, describe(), reach, reachSpread, overshoot, peak, peakSpread, bend);
        try {
            java.nio.file.Path file = humansFile();
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, text);
        } catch (Exception ignored) {
            // the settings file still has it
        }
    }

    /** Reads humans.txt if there is one; true if it set your hand. */
    private static boolean loadHumans() {
        try {
            java.nio.file.Path file = humansFile();
            if (!java.nio.file.Files.isRegularFile(file)) {
                return false;
            }
            Properties humans = new Properties();
            try (var reader = java.nio.file.Files.newBufferedReader(file)) {
                humans.load(reader);
            }
            if (!Boolean.parseBoolean(humans.getProperty("measured", "false"))) {
                return false;
            }
            set(num(humans, "stops_at", reach), num(humans, "stops_spread", reachSpread),
                    num(humans, "goes_past", overshoot), num(humans, "fastest_at", peak),
                    num(humans, "fastest_spread", peakSpread), num(humans, "bends", bend));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    static void load(Properties file) {
        asked = Boolean.parseBoolean(file.getProperty("hand.asked", String.valueOf(asked)));
        if (loadHumans()) {
            return;
        }
        if (Boolean.parseBoolean(file.getProperty("hand.measured", "false"))) {
            set(num(file, "hand.reach", reach), num(file, "hand.reachSpread", reachSpread),
                    num(file, "hand.overshoot", overshoot), num(file, "hand.peak", peak),
                    num(file, "hand.peakSpread", peakSpread), num(file, "hand.bend", bend));
        }
    }

    static void save(Properties file) {
        file.setProperty("hand.asked", String.valueOf(asked));
        file.setProperty("hand.measured", String.valueOf(measured));
        file.setProperty("hand.reach", String.valueOf(reach));
        file.setProperty("hand.reachSpread", String.valueOf(reachSpread));
        file.setProperty("hand.overshoot", String.valueOf(overshoot));
        file.setProperty("hand.peak", String.valueOf(peak));
        file.setProperty("hand.peakSpread", String.valueOf(peakSpread));
        file.setProperty("hand.bend", String.valueOf(bend));
    }

    private static double num(Properties file, String key, double fallback) {
        try {
            return Double.parseDouble(file.getProperty(key, String.valueOf(fallback)));
        } catch (NumberFormatException bad) {
            return fallback;
        }
    }

    private static double clamp(double value, double low, double high) {
        return Double.isNaN(value) ? (low + high) / 2 : Math.max(low, Math.min(high, value));
    }
}
