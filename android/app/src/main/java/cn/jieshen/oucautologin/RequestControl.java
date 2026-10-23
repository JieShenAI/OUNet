package cn.jieshen.oucautologin;

import android.os.SystemClock;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/** All XHA traffic, including failed attempts, goes through this single process meter. */
final class RequestControl {
    static final RequestControl GLOBAL=new RequestControl(SystemClock::elapsedRealtime);
    static final class Limited extends IOException {
        final long delayMs;
        Limited(long delay){super("等待下一次网络复检");delayMs=delay;}
    }
    final ReentrantLock schoolLock=new ReentrantLock(true);
    private final LongSupplier clock;
    private final RollingWindow school=new RollingWindow(),probes=new RollingWindow();
    private long lastProbe=-1;
    private volatile boolean authentication;
    RequestControl(LongSupplier clock){this.clock=clock;}
    static boolean school(URL url){return "https".equals(url.getProtocol())&&"xha.ouc.edu.cn".equals(url.getHost());}
    static String provider(URL url){String h=url.getHost();if(h.endsWith("msftconnecttest.com"))return "Microsoft";if(h.equals("captive.apple.com"))return "Apple";if(h.equals("firefox-portal-detection.com"))return "Mozilla";return "";}
    synchronized long delay(URL url,boolean important){
        if(school(url))return !important&&authentication?300:0;
        // No per-host/minute quota: only coalesce probes inside the minimum UI tick.
        return provider(url).isEmpty()||lastProbe<0?0:Math.max(0,lastProbe+300-clock.getAsLong());
    }
    synchronized void dispatched(URL url,boolean important)throws Limited {
        long wait=delay(url,important);if(wait>0)throw new Limited(wait);
        long now=clock.getAsLong();if(school(url))school.add(now);else if(!provider(url).isEmpty()){probes.add(now);lastProbe=now;}
    }
    void authentication(boolean value){authentication=value;}
    long total(){return school.total();}
    int minute(){return school.count(clock.getAsLong());}
    int probeMinute(){return probes.count(clock.getAsLong());}
}
