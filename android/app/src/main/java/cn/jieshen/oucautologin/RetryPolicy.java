package cn.jieshen.oucautologin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Persistent count of confirmed authentication refusals, never network attempts. */
public final class RetryPolicy {
    public static final int LIMIT = 3;
    public static final long WINDOW_MS = 10 * 60 * 1000L;
    public final List<Long> failures;
    public final boolean blocked;

    public RetryPolicy(List<Long> attempts, boolean blocked) {
        if (attempts == null || attempts.size() > LIMIT || (attempts.size() == LIMIT && !blocked)) {
            throw new IllegalArgumentException("Invalid retry state");
        }
        for (Long value : attempts) if (value == null || value < 0) throw new IllegalArgumentException("Invalid timestamp");
        this.failures = Collections.unmodifiableList(new ArrayList<>(attempts));
        this.blocked = blocked;
    }

    public static RetryPolicy empty() { return new RetryPolicy(Collections.emptyList(), false); }

    public RetryPolicy recordFailure(long now) {
        if (blocked) throw new IllegalStateException("Login paused");
        List<Long> recent = new ArrayList<>();
        for (long timestamp : failures) if (timestamp >= now - WINDOW_MS) recent.add(timestamp);
        recent.add(now);
        return new RetryPolicy(recent, recent.size() >= LIMIT);
    }
}
