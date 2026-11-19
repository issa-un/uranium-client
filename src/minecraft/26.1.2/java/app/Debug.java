package app;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Debug mode: watches everything the modules do that a server's anticheat could look at,
 * and says what looks off. Every module mouse move is kept with its full path (the view
 * turns it sent, and Replenish's cursor trips), and every slot switch, click and placement
 * with when it happened. Each one is checked for the things anticheats catch:
 * - a snap: a huge turn in a single mouse report,
 * - a move at an even speed (a hand speeds up and slows down),
 * - the same timing over and over, or landing dead on every time,
 * - two slot switches in one tick, or the same slot sent twice,
 * - turning a long way and clicking in the same tick,
 * - two right-clicks in one tick.
 * Human % starts at 100 and drops for each of those in the last minute (the newest count
 * most). It records only while Debug is on; render thread only.
 */
final class Debug {
    private static final int KEEP_MOVES = 24;
    private static final int KEEP_NOTES = 60;
    private static final long WINDOW_MS = 60_000;
    private static final double SNAP_DEGREES = 25;
    private static volatile boolean enabled;

    /** One mouse move a module made. */
    static final class Move {
        final String who;
        final String kind;
        final long startMs;
        long plannedMs;
        final double plannedDegrees;
        // Where a flick's main push was set to stop (share of the way); NaN for other moves.
        final double reach;
        final boolean cursor;
        // {ms since start, x, y}: view turns in degrees, or the cursor in window pixels.
        final List<double[]> points = new ArrayList<>();
        long endMs = -1;
        final List<String> issues = new ArrayList<>();

        Move(String who, String kind, double degrees, long plannedMs, double reach, boolean cursor) {
            this.who = who;
            this.kind = kind;
            this.plannedDegrees = degrees;
            this.plannedMs = plannedMs;
            this.reach = reach;
            this.cursor = cursor;
            this.startMs = now();
        }

        long tookMs() {
            return (endMs < 0 ? now() : endMs) - startMs;
        }
    }

    /** Something worth a line: what happened, and how much it costs Human % (0: just info). */
    record Note(long ms, String who, String text, int weight) {}

    private static final Deque<Move> MOVES = new ArrayDeque<>();
    private static final Deque<Note> NOTES = new ArrayDeque<>();
    // Module slot sends and clicks, for the one-tick checks.
    private static long lastSlotMs = -1000;
    private static int lastSlot = -1;
    private static long lastClickMs = -1000;
    // View turned by modules lately: {ms, degrees}.
    private static final Deque<double[]> TURNS = new ArrayDeque<>();

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("CartHighTier", "High Tier"), Map.entry("CartLowTier", "Low Tier"),
            Map.entry("CartBlock", "Block"), Map.entry("CartRefill", "Replenish"),
            Map.entry("CrossbowReload", "Auto reload"), Map.entry("KnockbackSteer", "Knockback Displacement"),
            Map.entry("AimHelper", "Aim Assist"), Map.entry("MaceSwap", "Mace Swap"),
            Map.entry("ShieldBreak", "Auto Stun"), Map.entry("SlamSteer", "Target Strafe"),
            Map.entry("PearlCatch", "Pearl Catch"), Map.entry("DebugScreen", "Debug test"),
            Map.entry("MinecraftSpearDash", "Spear Dash"));

    private Debug() {}

    static boolean isEnabled() {
        return enabled;
    }

    static void setEnabled(boolean on) {
        enabled = on;
    }

    static void toggleEnabled() {
        enabled = !enabled;
    }

    static long now() {
        return System.nanoTime() / 1_000_000;
    }

    /** The module that called, by its menu name. */
    static String caller() {
        return StackWalker.getInstance().walk(frames -> frames
                .map(StackWalker.StackFrame::getClassName)
                .map(name -> name.substring(name.lastIndexOf('.') + 1))
                .map(name -> name.contains("$") ? name.substring(0, name.indexOf('$')) : name)
                .filter(name -> !name.equals("Debug") && !name.equals("HumanAim") && !name.equals("MouseSteps")
                        && !name.equals("Cart") && !name.equals("Mc"))
                .findFirst()
                .map(name -> NAMES.getOrDefault(name, name))
                .orElse("?"));
    }

    // ---- moves ----

    /** A view move starting; null while Debug is off. */
    static Move begin(String kind, double yaw, double pitch, long plannedMs, double reach) {
        if (!enabled) {
            return null;
        }
        Move move = new Move(caller(), kind, Math.hypot(yaw, pitch), plannedMs, reach, false);
        keep(move);
        return move;
    }

    /** A cursor trip starting (Replenish); null while Debug is off. */
    static Move beginCursor(String who, double pixels, long plannedMs) {
        if (!enabled) {
            return null;
        }
        Move move = new Move(who, "cursor", pixels, plannedMs, Double.NaN, true);
        keep(move);
        return move;
    }

    private static void keep(Move move) {
        MOVES.addFirst(move);
        while (MOVES.size() > KEEP_MOVES) {
            MOVES.removeLast();
        }
    }

    /** One mouse report of a view move: this many degrees. */
    static void turn(Move move, double yaw, double pitch) {
        if (move == null) {
            return;
        }
        double sumYaw = yaw;
        double sumPitch = pitch;
        if (!move.points.isEmpty()) {
            double[] before = move.points.get(move.points.size() - 1);
            sumYaw += before[1];
            sumPitch += before[2];
        }
        move.points.add(new double[] {now() - move.startMs, sumYaw, sumPitch});
        double size = Math.hypot(yaw, pitch);
        TURNS.addLast(new double[] {now(), size});
        while (TURNS.size() > 200) {
            TURNS.removeFirst();
        }
        if (size > SNAP_DEGREES) {
            issue(move, String.format(Locale.ROOT, "snap: %.0f° in one mouse report", size), 15);
        }
    }

    /** One cursor point of a cursor trip, in window pixels. */
    static void point(Move move, double x, double y) {
        if (move != null) {
            move.points.add(new double[] {now() - move.startMs, x, y});
        }
    }

    /** The move is over: checks its speed, its timing against the last ones, and its landing. */
    static void end(Move move) {
        if (move == null || move.endMs >= 0) {
            return;
        }
        move.endMs = now();
        if (move.cursor) {
            return;
        }
        // Even speed: the reports all about the same size.
        List<Double> sizes = new ArrayList<>();
        double[] before = {0, 0, 0};
        for (double[] p : move.points) {
            sizes.add(Math.hypot(p[1] - before[1], p[2] - before[2]));
            before = p;
        }
        if (sizes.size() >= 8) {
            sizes.remove(sizes.size() - 1);
            double mean = 0;
            for (double s : sizes) {
                mean += s;
            }
            mean /= sizes.size();
            double var = 0;
            for (double s : sizes) {
                var += (s - mean) * (s - mean);
            }
            double cv = mean > 0 ? Math.sqrt(var / sizes.size()) / mean : 0;
            if (cv < 0.12) {
                issue(move, "even speed the whole way (a hand speeds up and slows down)", 8);
            }
        }
        // The same timing as the last moves of its kind.
        int same = 0;
        int seen = 0;
        for (Move other : MOVES) {
            if (other == move || other.endMs < 0 || !other.who.equals(move.who) || !other.kind.equals(move.kind)) {
                continue;
            }
            if (++seen > 3) {
                break;
            }
            if (Math.abs(other.tookMs() - move.tookMs()) <= 2) {
                same++;
            }
        }
        if (same >= 3) {
            issue(move, "took the same time as its last 3 moves (" + move.tookMs() + " ms)", 8);
        }
        // Landing dead on, flick after flick.
        if (!Double.isNaN(move.reach)) {
            int exact = 0;
            int flicks = 0;
            for (Move other : MOVES) {
                if (Double.isNaN(other.reach) || other.endMs < 0) {
                    continue;
                }
                if (++flicks > 6) {
                    break;
                }
                if (Math.abs(other.reach - 1) < 0.005) {
                    exact++;
                }
            }
            if (flicks >= 6 && exact >= 6) {
                issue(move, "6 flicks in a row landed dead on", 8);
            }
        }
    }

    private static void issue(Move move, String text, int weight) {
        if (!move.issues.contains(text)) {
            move.issues.add(text);
            note(move.who, text, weight);
        }
    }

    // ---- actions ----

    /** A module sent a hotbar slot to the server. */
    static void slot(int slot, boolean changed) {
        if (!enabled) {
            return;
        }
        long now = now();
        String who = caller();
        if (!changed) {
            note(who, "sent hotbar " + (slot + 1) + " again (the server already had it)", 20);
        } else if (now - lastSlotMs < 50 && lastSlot != slot) {
            note(who, "switched slots twice in one tick (to " + (lastSlot + 1) + ", then " + (slot + 1) + ")", 5);
        } else {
            note(who, "switched to hotbar " + (slot + 1), 0);
        }
        lastSlotMs = now;
        lastSlot = slot;
    }

    /** A module clicked or placed something. */
    static void click(String what) {
        if (!enabled) {
            return;
        }
        long now = now();
        String who = caller();
        double turned = 0;
        for (double[] turn : TURNS) {
            if (now - turn[0] < 50) {
                turned += turn[1];
            }
        }
        if (now - lastClickMs < 50) {
            note(who, what + ": the second click in one tick", 10);
        } else if (turned > 20) {
            note(who, String.format(Locale.ROOT, "%s: turned %.0f° and clicked in the same tick", what, turned), 8);
        } else {
            note(who, what, 0);
        }
        lastClickMs = now;
    }

    /** Something to show that costs nothing (a click held back, a macro press). */
    static void info(String text) {
        if (enabled) {
            note(caller(), text, 0);
        }
    }

    private static void note(String who, String text, int weight) {
        NOTES.addFirst(new Note(now(), who, text, weight));
        while (NOTES.size() > KEEP_NOTES) {
            NOTES.removeLast();
        }
        if (weight > 0) {
            SpearLog.log("debug: " + who + ": " + text);
        }
    }

    // ---- reading ----

    /** 100 minus what looked off in the last minute, newest counting most. */
    static int humanPercent() {
        long now = now();
        double lost = 0;
        for (Note note : NOTES) {
            double age = now - note.ms();
            if (age < WINDOW_MS) {
                lost += note.weight() * (1 - age / WINDOW_MS);
            }
        }
        return (int) Math.max(0, Math.round(100 - lost));
    }

    static int flagsLastMinute() {
        long now = now();
        int count = 0;
        for (Note note : NOTES) {
            if (note.weight() > 0 && now - note.ms() < WINDOW_MS) {
                count++;
            }
        }
        return count;
    }

    static List<Move> moves() {
        return new ArrayList<>(MOVES);
    }

    static List<Note> notes() {
        return new ArrayList<>(NOTES);
    }

    static void clear() {
        MOVES.clear();
        NOTES.clear();
        TURNS.clear();
    }

    /** Called by the HUD: a small Human % badge while Debug is on. */
    static void drawBadge(net.minecraft.client.gui.GuiGraphicsExtractor graphics, net.minecraft.client.Minecraft minecraft) {
        if (!enabled || Mc.screen(minecraft) != null) {
            return;
        }
        int human = humanPercent();
        int flags = flagsLastMinute();
        String text = "Debug · Human " + human + "%" + (flags > 0 ? " · " + flags + " could flag" : "");
        int color = human >= 85 ? 0xFF39FF14 : human >= 60 ? 0xFFFFD54F : 0xFFFF5252;
        graphics.fill(2, 2, 8 + minecraft.font.width(text), 14, 0x90000000);
        graphics.text(minecraft.font, text, 5, 4, color, false);
    }
}
