package dev.briefestboxer.core;

/** Keeps a just-selected target briefly through transient ray misses. */
public final class EntityTargetGrace<T> {
    private T target;
    private long lastSeenNanos;

    public void remember(T target, long nowNanos) {
        if (target == null) throw new IllegalArgumentException("target is required");
        this.target = target;
        this.lastSeenNanos = nowNanos;
    }

    public T duringMiss(long nowNanos, long graceNanos) {
        if (graceNanos < 0L) throw new IllegalArgumentException("grace must be non-negative");
        if (target != null && nowNanos >= lastSeenNanos && nowNanos - lastSeenNanos <= graceNanos) {
            return target;
        }
        clear();
        return null;
    }

    public void clear() {
        target = null;
        lastSeenNanos = 0L;
    }
}
