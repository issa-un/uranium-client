package app;

import java.util.concurrent.ThreadLocalRandom;

/**
 * A number setting the menu shows as a slider: either a min-max delay in ms, where every
 * use picks a random time in between (so no two combos have exactly the same timing), or a
 * single value such as a chance in percent. Read from the render and network threads.
 */
final class Num {
    final boolean range;
    final int limit;
    private final int step;
    private final String unit;
    private final int defaultLow;
    private final int defaultHigh;
    // A pitch: the slider runs over raw tenths of a degree, PITCH_ZERO being level.
    private final boolean pitch;
    // Stored as tenths, shown as the value (45 shows as 4.5).
    private final boolean inTenths;
    private volatile int low;
    private volatile int high;

    private Num(boolean range, int limit, int step, String unit, int low, int high) {
        this(range, limit, step, unit, low, high, false);
    }

    private Num(boolean range, int limit, int step, String unit, int low, int high, boolean pitch) {
        this(range, limit, step, unit, low, high, pitch, false);
    }

    private Num(boolean range, int limit, int step, String unit, int low, int high, boolean pitch,
            boolean inTenths) {
        this.inTenths = inTenths;
        this.pitch = pitch;
        this.range = range;
        this.limit = limit;
        this.step = step;
        this.unit = unit;
        defaultLow = low;
        defaultHigh = high;
        set(low, high);
    }

    /** A delay picked at random between low and high ms each time, up to limit. */
    static Num ms(int low, int high, int limit) {
        return new Num(true, limit, 5, " ms", low, high);
    }

    /** Clicks per second, picked at random between low and high each click, up to limit. */
    static Num cps(int low, int high, int limit) {
        return new Num(true, limit, 1, " CPS", low, high);
    }

    private static final int PITCH_ZERO = 300;

    /**
     * A view pitch range, picked at random between low and high each time, from 30 degrees up
     * (negative, as the game counts it) to 30 down, in half-degree steps.
     */
    static Num pitchRange(double low, double high) {
        return new Num(true, 2 * PITCH_ZERO, 5, "°", toRaw(low), toRaw(high), true);
    }

    /** For a pitch range: a pitch from low to high, in degrees. */
    float pickPitch() {
        return (pick() - PITCH_ZERO) / 10.0F;
    }

    /** For a pitch range: sets both ends, in degrees. */
    void setPitches(double low, double high) {
        set(toRaw(low), toRaw(high));
    }

    private static int toRaw(double degrees) {
        return (int) Math.round(degrees * 10) + PITCH_ZERO;
    }

    private static String degrees(int raw) {
        int tenths = raw - PITCH_ZERO;
        return (tenths % 10 == 0 ? String.valueOf(tenths / 10) : String.valueOf(tenths / 10.0)) + "°";
    }

    /** A chance from 0 to 100%. */
    static Num percent(int value) {
        return new Num(false, 100, 1, "%", value, value);
    }

    /** A single value from 0 to limit in steps, shown with its unit (like 600°/s). */
    static Num value(int value, int limit, int step, String unit) {
        return new Num(false, limit, step, unit, value, value);
    }

    /** A single value in tenths, from 0 to max (like 4.5 blocks); read it with tenths(). */
    static Num tenths(double value, double max, String unit) {
        int raw = (int) Math.round(value * 10);
        return new Num(false, (int) Math.round(max * 10), 1, unit, raw, raw, false, true);
    }

    /** For a tenths value: the value itself. */
    double tenths() {
        return low / 10.0;
    }

    int low() {
        return low;
    }

    int high() {
        return high;
    }

    /** Snaps both ends to the slider's steps; a single value keeps high equal to low. */
    void set(int newLow, int newHigh) {
        int l = snap(newLow);
        int h = range ? snap(newHigh) : l;
        low = Math.min(l, h);
        high = Math.max(l, h);
    }

    /** Dragging the low knob past the high one pushes it along. */
    void setLow(int value) {
        int l = snap(value);
        set(l, range ? Math.max(l, high) : l);
    }

    void setHigh(int value) {
        int h = snap(value);
        set(Math.min(low, h), h);
    }

    void reset() {
        set(defaultLow, defaultHigh);
    }

    /** A delay for one use: anywhere from low to high. */
    int pick() {
        int l = low;
        int h = high;
        return h <= l ? l : ThreadLocalRandom.current().nextInt(l, h + 1);
    }

    /** For a chance: true that percent of the time. */
    boolean roll() {
        int percent = low;
        return percent >= 100 || ThreadLocalRandom.current().nextInt(100) < percent;
    }

    /** No delay at all. */
    boolean isZero() {
        return high == 0;
    }

    String label() {
        if (pitch) {
            return low == high ? degrees(low) : degrees(low) + " to " + degrees(high);
        }
        if (!range) {
            return (inTenths ? String.valueOf(low / 10.0) : String.valueOf(low)) + unit;
        }
        if (high == 0) {
            return "Off";
        }
        return low == high ? low + unit : low + "-" + high + unit;
    }

    private int snap(int value) {
        int clamped = Math.max(0, Math.min(limit, value));
        return Math.min(limit, Math.round(clamped / (float) step) * step);
    }
}
