package cn.jieshen.oucautologin;

import java.util.UUID;

/** One outage per actual connection, independent of account/configuration changes. */
final class RecoveryTracker {
    static final class Outage {
        final String id=UUID.randomUUID().toString(), key, ssid;
        final long detectedAt, detectedElapsed;
        int attempts;
        Outage(String key,String ssid,long wall,long elapsed){this.key=key;this.ssid=ssid;detectedAt=wall;detectedElapsed=elapsed;}
    }
    private Outage active;
    synchronized Outage offline(String key,String ssid,long wall,long elapsed){
        if(active==null||!active.key.equals(key))active=new Outage(key,ssid,wall,elapsed);
        return active;
    }
    synchronized Outage current(String key){return active!=null&&active.key.equals(key)?active:null;}
    synchronized void attempt(String key){Outage o=current(key);if(o!=null)o.attempts++;}
    synchronized void close(String id){if(active!=null&&active.id.equals(id))active=null;}
    synchronized void connection(String key){if(active!=null&&!active.key.equals(key))active=null;}
    static long duration(Outage o,long elapsed){return Math.max(0,elapsed-o.detectedElapsed);}
}
