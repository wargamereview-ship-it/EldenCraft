package dev.eldencraft.link;

import static java.lang.foreign.ValueLayout.*;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;
import dev.eldencraft.combat.NativeStatus;

/** Immutable, bounded status snapshot. Kept independent of the Windows link for codec tests. */
public final class NativeStatusSnapshot {
    private static final VarHandle INT = JAVA_INT.varHandle();
    private final Entry[] entries;
    private NativeStatusSnapshot(Entry[] entries) { this.entries = entries; }
    public Entry get(NativeStatus status) { return entries[status.ordinal()]; }

    public record Entry(int buildup, int maximum, float remaining, float duration) {
        public boolean active() { return remaining > 0 || remaining == -1; }
        public float buildupFraction() { return maximum <= 0 ? 0 : Math.clamp((float) buildup / maximum, 0, 1); }
        public float timerFraction() { return duration <= 0 || remaining == -1 ? 1 : Math.clamp(remaining / duration, 0, 1); }
        public int effectTicks() { return remaining == -1 ? -1 : (int) Math.ceil(Math.max(0, remaining) * 20.0); }
    }

    /** Reads a slice beginning at OFF_PLAYER_STATUSES, guarded by its sequence and the current life. */
    public static NativeStatusSnapshot read(MemorySegment s, long now, int epoch, int world, boolean alive) {
        if (!alive || epoch == 0 || s.byteSize() < Proto.PLAYER_STATUSES_BYTES) return null;
        for (int attempt = 0; attempt < 4; attempt++) {
            int seq = (int) INT.getAcquire(s, 0L);
            if ((seq & 1) != 0) continue;
            int nativeEpoch = s.get(JAVA_INT, 4), nativeWorld = s.get(JAVA_INT, 8), flags = s.get(JAVA_INT, 12);
            long updated = s.get(JAVA_LONG, 16);
            Entry[] entries = new Entry[Proto.STATUS_COUNT];
            boolean valid = true;
            for (int i = 0; i < entries.length; i++) {
                long offset = i * 4L;
                int buildup = s.get(JAVA_INT, Proto.PS_BUILDUP + offset), maximum = s.get(JAVA_INT, Proto.PS_MAXIMUM + offset);
                float remaining = s.get(JAVA_FLOAT, Proto.PS_REMAINING + offset), duration = s.get(JAVA_FLOAT, Proto.PS_DURATION + offset);
                valid &= maximum >= 0 && maximum <= 100_000 && buildup >= 0 && buildup <= maximum
                    && Float.isFinite(remaining) && (remaining == -1 || remaining >= 0 && remaining <= 36_000)
                    && Float.isFinite(duration) && duration >= 0 && duration <= 36_000;
                entries[i] = new Entry(buildup, maximum, remaining, duration);
            }
            VarHandle.loadLoadFence();
            if ((int) INT.getAcquire(s, 0L) != seq) continue;
            long age = now - updated;
            return !valid || flags != Proto.STATUSES_VALID || nativeEpoch != epoch || nativeWorld != world
                || age < 0 || age > 1000 ? null : new NativeStatusSnapshot(entries);
        }
        return null;
    }
}
