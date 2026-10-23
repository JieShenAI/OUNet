package cn.jieshen.oucautologin;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Transactional launch-scoped ledger; never inferred from the truncated diagnostic log. */
final class RecoveryRepository extends SQLiteOpenHelper {
    private static RecoveryRepository instance;
    final String launchId=UUID.randomUUID().toString();
    final long startedAt=System.currentTimeMillis();
    private final RecoveryTracker tracker=new RecoveryTracker();
    private volatile String error="";
    private volatile long revision;
    long revision(){return revision;}
    static synchronized RecoveryRepository get(Context context){
        if(instance==null)instance=new RecoveryRepository(context.getApplicationContext());return instance;
    }
    private RecoveryRepository(Context context){super(context,"recovery_v1.db",null,1);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE recovery (id TEXT PRIMARY KEY, launch_id TEXT NOT NULL, outage_id TEXT NOT NULL, detected_at INTEGER NOT NULL, recovered_at INTEGER NOT NULL, duration_ms INTEGER NOT NULL, kind TEXT NOT NULL, ssid TEXT NOT NULL, account TEXT NOT NULL, reason TEXT NOT NULL, attempts INTEGER NOT NULL)");
        db.execSQL("CREATE UNIQUE INDEX one_recovery_per_outage ON recovery(launch_id,outage_id)");
        db.execSQL("CREATE INDEX launch_timeline ON recovery(launch_id,recovered_at DESC)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){throw new IllegalStateException("Unsupported recovery schema");}
    synchronized void connection(String key){tracker.connection(key);}
    synchronized void offline(String key,String ssid){tracker.offline(key,ssid,System.currentTimeMillis(),SystemClock.elapsedRealtime());}
    synchronized void attempt(String key){tracker.attempt(key);}
    synchronized boolean hasOutage(String key){return tracker.current(key)!=null;}
    synchronized void recovered(String key,boolean accepted,String account,String reason){
        RecoveryTracker.Outage o=tracker.current(key);if(o==null)return;
        try{
            SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
            try{
                ContentValues v=new ContentValues();v.put("id",UUID.randomUUID().toString());v.put("launch_id",launchId);v.put("outage_id",o.id);
                v.put("detected_at",o.detectedAt);v.put("recovered_at",System.currentTimeMillis());v.put("duration_ms",RecoveryTracker.duration(o,SystemClock.elapsedRealtime()));
                v.put("kind",accepted?"ASSISTED":"EXTERNAL");v.put("ssid",o.ssid);v.put("account",accepted?mask(account):"—");v.put("reason",reason);v.put("attempts",o.attempts);
                db.insertOrThrow("recovery",null,v);db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            tracker.close(o.id);error="";revision++;
        }catch(RuntimeException e){error="恢复记录未能保存";}
    }
    static String mask(String value){if(value==null||value.isEmpty())return "—";if(value.length()<5)return "****";return value.substring(0,2)+"****"+value.substring(value.length()-2);}
    String error(){return error;}
    synchronized int count(){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM recovery WHERE launch_id=? AND kind='ASSISTED'",new String[]{launchId})){return c.moveToFirst()?c.getInt(0):0;}
        catch(RuntimeException e){error="恢复记录暂不可用";return 0;}
    }
    static final class Event {
        final long detectedAt,recoveredAt,durationMs;final String kind,ssid,account,reason;final int attempts;
        Event(Cursor c){detectedAt=c.getLong(0);recoveredAt=c.getLong(1);durationMs=c.getLong(2);kind=c.getString(3);ssid=c.getString(4);account=c.getString(5);reason=c.getString(6);attempts=c.getInt(7);}
    }
    synchronized List<Event> page(int offset,int limit){
        List<Event> events=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT detected_at,recovered_at,duration_ms,kind,ssid,account,reason,attempts FROM recovery WHERE launch_id=? ORDER BY recovered_at DESC,id DESC LIMIT ? OFFSET ?",new String[]{launchId,Integer.toString(Math.min(100,Math.max(1,limit))),Integer.toString(Math.max(0,offset))})){
            while(c.moveToNext())events.add(new Event(c));
        }catch(RuntimeException e){error="恢复记录暂不可用";}
        return events;
    }
}
