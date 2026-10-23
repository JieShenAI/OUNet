package cn.jieshen.oucautologin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;

public final class MonitorService extends Service {
    static volatile boolean running;
    static volatile long nextCheckElapsed;
    private static final String CHANNEL="campus_monitor";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private WifiAccess.Observer observer;
    private boolean receiverRegistered;
    private long started;
    private long scheduleRevision;
    private final Runnable periodic=this::checkNow;
    private final Runnable event=this::checkNow;
    private final BroadcastReceiver unlock=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent intent){networkEvent();}
    };
    public static void start(Context context){context.startForegroundService(new Intent(context,MonitorService.class));}
    public static void reschedule(Context context){if(running)context.startForegroundService(new Intent(context,MonitorService.class).setAction("RESCHEDULE"));}
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,1,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,2,new Intent(this,MonitorService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_network).setContentTitle("OUNet正在监测")
            .setContentText("本地评估"+new AppStore(this).intervalText()+"秒 · 断网轮换探测")
            .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(new Notification.Action.Builder(null,"停止监测",stop).build()).build();
    }
    @Override public void onCreate(){
        super.onCreate();NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"校园网后台监测",NotificationManager.IMPORTANCE_LOW));
        if(Build.VERSION.SDK_INT>=34)startForeground(10,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(10,notification());
        running=true;observer=new WifiAccess.Observer(this,()->handler.post(this::networkEvent));
        if(Build.VERSION.SDK_INT>=33)registerReceiver(unlock,new IntentFilter(Intent.ACTION_USER_PRESENT),Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(unlock,new IntentFilter(Intent.ACTION_USER_PRESENT));
        receiverRegistered=true;
    }
    private void networkEvent(){
        if(!running)return;
        handler.removeCallbacks(event);handler.postDelayed(event,300);
    }
    private void plan(long due){
        handler.removeCallbacks(periodic);nextCheckElapsed=due;
        handler.postDelayed(periodic,Math.max(0,due-SystemClock.elapsedRealtime()));
    }
    private void checkNow(){
        if(!running||!new AppStore(this).enabled())return;
        handler.removeCallbacks(periodic);nextCheckElapsed=0;started=SystemClock.elapsedRealtime();
        final long revision=++scheduleRevision,checkStarted=started;
        CheckCoordinator.request(this,RunGate.AUTOMATIC,()->{
            if(running&&revision==scheduleRevision&&new AppStore(this).enabled())
                plan(CheckSchedule.nextMillis(checkStarted,SystemClock.elapsedRealtime(),new AppStore(this).intervalMs()));
        });
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        AppStore store=new AppStore(this);
        if(intent!=null&&"STOP".equals(intent.getAction())){
            try{store.setEnabled(false);store.status("自动监测已停止");}catch(IOException e){store.status(e.getMessage());}
            CheckCoordinator.cancel();stopSelf();return START_NOT_STICKY;
        }
        if(!store.enabled()){stopSelf();return START_NOT_STICKY;}
        if(intent!=null&&"RESCHEDULE".equals(intent.getAction())){
            scheduleRevision++;handler.removeCallbacks(event);plan(SystemClock.elapsedRealtime()+store.intervalMs());
        }
        else checkNow();
        return START_STICKY;
    }
    @Override public void onDestroy(){
        running=false;scheduleRevision++;nextCheckElapsed=0;handler.removeCallbacksAndMessages(null);
        if(observer!=null)observer.close();if(receiverRegistered)unregisterReceiver(unlock);
        CheckCoordinator.cancelAutomatic();super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
