package cn.jieshen.oucautologin;

import java.util.ArrayDeque;

/** Monotonic sliding window; timestamp at exactly 60 seconds has expired. */
final class RollingWindow {
    private final ArrayDeque<Long> times=new ArrayDeque<>();
    private long total;
    synchronized void add(long now){prune(now);times.addLast(now);total++;}
    synchronized int count(long now){prune(now);return times.size();}
    synchronized long total(){return total;}
    synchronized long delay(long now,int maximum){prune(now);return times.size()<maximum?0:Math.max(1,times.peekFirst()+60000-now);}
    private void prune(long now){while(!times.isEmpty()&&now-times.peekFirst()>=60000)times.removeFirst();}
}
