package cn.jieshen.oucautologin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded persistent cooldown; one trial when due, with no permanent retry limit. */
public final class RetryPolicy {
    public static final int LIMIT = 3;
    public static final long WINDOW_MS = 10 * 60 * 1000L;
    public final List<Long> failures;
    public final boolean blocked;
    public static final long[] DELAYS_MS={15000,30000,60000,120000,300000};
    public final int level;
    public final long nextRetryAt;

    public RetryPolicy(List<Long> attempts, boolean blocked) {
        this(attempts,blocked,blocked?1:0,blocked?System.currentTimeMillis()+DELAYS_MS[0]:0);
    }
    public RetryPolicy(List<Long> attempts, boolean blocked,int level,long nextRetryAt) {
        if (attempts == null || attempts.size() > LIMIT || (attempts.size() == LIMIT && !blocked)) {
            throw new IllegalArgumentException("Invalid retry state");
        }
        for (Long value : attempts) if (value == null || value < 0) throw new IllegalArgumentException("Invalid timestamp");
        this.failures = Collections.unmodifiableList(new ArrayList<>(attempts));
        this.blocked = blocked;
        if(level<0||level>DELAYS_MS.length||nextRetryAt<0||(blocked&&level==0)||(!blocked&&(level!=0||nextRetryAt!=0)))throw new IllegalArgumentException("Invalid cooldown");
        this.level=level;this.nextRetryAt=nextRetryAt;
    }

    public static RetryPolicy empty() { return new RetryPolicy(Collections.emptyList(), false); }

    public RetryPolicy recordFailure(long now) {
        if (blocked) {
            if(!canAttempt(now))throw new IllegalStateException("Login cooling down");
            int next=Math.min(DELAYS_MS.length,level+1);
            return new RetryPolicy(failures,true,next,now+DELAYS_MS[next-1]);
        }
        List<Long> recent = new ArrayList<>();
        for (long timestamp : failures) if (timestamp >= now - WINDOW_MS) recent.add(timestamp);
        recent.add(now);
        boolean paused=recent.size()>=LIMIT;
        return new RetryPolicy(recent,paused,paused?1:0,paused?now+DELAYS_MS[0]:0);
    }
    public boolean canAttempt(long now){return !blocked||remainingMillis(now)==0;}
    public long remainingMillis(long now){return blocked?Math.max(0,Math.min(DELAYS_MS[level-1],nextRetryAt-now)):0;}
    public RetryPolicy expedite(long now){return blocked?new RetryPolicy(failures,true,level,now):this;}
}
