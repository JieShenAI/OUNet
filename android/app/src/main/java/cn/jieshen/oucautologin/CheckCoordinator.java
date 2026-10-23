package cn.jieshen.oucautologin;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Activity and service share one worker, one in-flight run, and immutable inputs. */
public final class CheckCoordinator {
    private static final Object LOCK=new Object();
    private static final RunGate gate=new RunGate();
    // At most one replacement can wait while a native socket/DNS call is being cancelled.
    private static final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1));
    private static final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor();
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static final Map<RunGate.Ticket,List<Runnable>> completions=new IdentityHashMap<>();
    private static volatile WifiTransport activeTransport;
    private static volatile Thread activeThread;
    private static volatile RunGate.Ticket executing;
    private static volatile boolean foreground;
    private static Runnable deferred;
    private static boolean deferredBackground;
    private static final class QueuedCheck implements Runnable {
        final Context context;
        final SettingsData config;
        final RunGate.Ticket ticket;
        QueuedCheck(Context context,SettingsData config,RunGate.Ticket ticket){this.context=context;this.config=config;this.ticket=ticket;}
        @Override public void run(){CheckCoordinator.run(context,config,ticket);}
    }
    static boolean foreground(){return foreground;}
    public static void foreground(boolean value){foreground=value;if(!value)synchronized(LOCK){if(!deferredBackground)clearDeferred();}}
    public static boolean running(){return gate.current()!=null;}
    public static boolean reconnecting(){RunGate.Ticket t=gate.current();return t!=null&&!t.cancelled&&t.userReconnect;}
    public static void reconnect(Context context,Runnable completion)throws IOException{
        Context app=context.getApplicationContext();AppStore store=new AppStore(app);SettingsData config=store.config();
        synchronized(LOCK){
            RunGate.Ticket current=gate.current();
            if(current!=null&&!current.cancelled&&current.userReconnect&&current.generation==config.generation){
                List<Runnable> callbacks=completions.get(current);if(completion!=null&&callbacks!=null&&!callbacks.contains(completion))callbacks.add(completion);return;
            }
            cancel();DeviceRepository.invalidate();
            store.resetRetry(config);ConnectionStatus.invalidate("用户重新连接，正在请求XHA");
            store.log("用户重新连接：失败保护已重置");
            RunGate.Ticket ticket=gate.admit(config.generation,RunGate.MANUAL);ticket.userReconnect=true;ticket.reason="用户重新连接";
            List<Runnable> callbacks=new ArrayList<>();if(completion!=null)callbacks.add(completion);completions.put(ticket,callbacks);
            worker.execute(new QueuedCheck(app,config,ticket));
        }
    }
    public static void request(Context context,int mode,Runnable completion) {
        request(context,mode,mode==RunGate.AUTOMATIC?"周期检测":mode==RunGate.MANUAL?"手动连接":"启动检测",completion);
    }
    public static void request(Context context,int mode,String reason,Runnable completion) {
        Context app=context.getApplicationContext(); AppStore store=new AppStore(app); SettingsData config;
        try{config=store.config();}catch(IOException e){store.status(e.getMessage());if(completion!=null)main.post(completion);return;}
        synchronized(LOCK) {
            RunGate.Ticket before=gate.current(),ticket=gate.admit(config.generation,mode);
            if(before!=ticket||mode>=ticket.mode)ticket.reason=reason;
            List<Runnable> listeners=completions.get(ticket);
            if(listeners==null){listeners=new ArrayList<>();completions.put(ticket,listeners);}
            if(completion!=null&&!listeners.contains(completion))listeners.add(completion);
            if(ticket==before)return;
            if(before!=null)interrupt(before);
            discardQueued();
            worker.execute(new QueuedCheck(app,config,ticket));
        }
    }
    private static void run(Context context,SettingsData config,RunGate.Ticket ticket) {
        Thread.interrupted(); executing=ticket; activeThread=Thread.currentThread();
        long started=SystemClock.elapsedRealtime();
        java.util.concurrent.ScheduledFuture<?> timeout=deadlines.schedule(()->{
            synchronized(LOCK){if(executing==ticket){
                if(gate.valid(ticket,config.generation)){
                    String message="本轮达到60秒时限，等待重新检查";
                    new AppStore(context).statusIfCurrent(config,message);ConnectionStatus.Value current=ConnectionStatus.get();
                    ConnectionStatus.set(current.state==ConnectionStatus.State.CHECKING?ConnectionStatus.State.INDETERMINATE:current.state,current.key,config.generation,message);
                }
                ticket.cancelled=true;interrupt(ticket);
            }}
        },60,TimeUnit.SECONDS);
        try {
            check(context,config,ticket);
            LoginEngine.check(context,config,ticket,started);
        }catch(InterruptedIOException e) {
            report(context,config,ticket,"网络连接已变化或请求时限已结束；本轮已停止");
        }catch(IOException e){report(context,config,ticket,e.getMessage());}
        catch(Exception e){report(context,config,ticket,"本轮检查异常，已停止；请检查权限和本地配置");}
        finally {
            timeout.cancel(false);
            synchronized(LOCK){
                if(executing==ticket){executing=null;activeTransport=null;activeThread=null;}
                gate.finish(ticket);List<Runnable> callbacks=completions.remove(ticket);
                if(callbacks!=null)for(Runnable callback:callbacks)main.post(callback);
            }
            Thread.interrupted();
        }
    }
    static void transport(RunGate.Ticket ticket,WifiTransport transport) throws InterruptedIOException {
        synchronized(LOCK){if(ticket.cancelled)throw new InterruptedIOException();activeTransport=transport;}
    }
    static void check(Context context,SettingsData config,RunGate.Ticket ticket) throws InterruptedIOException {
        if(Thread.currentThread().isInterrupted()||!gate.valid(ticket,config.generation)||!new AppStore(context).isCurrent(config))throw new InterruptedIOException("旧轮已取消");
    }
    static void report(Context context,SettingsData config,RunGate.Ticket ticket,String message) {
        synchronized(LOCK){try{check(context,config,ticket);new AppStore(context).statusIfCurrent(config,message);
            ConnectionStatus.Value current=ConnectionStatus.get();
            ConnectionStatus.set(current.generation==config.generation?current.state:ConnectionStatus.State.INDETERMINATE,current.key,config.generation,message);
        }catch(InterruptedIOException ignored){}}
    }
    static void publish(Context context,SettingsData config,RunGate.Ticket ticket,ConnectionStatus.State state,String key,String detail)throws InterruptedIOException {
        synchronized(LOCK){synchronized(AppStore.LOCK){check(context,config,ticket);ConnectionStatus.set(state,key,config.generation,detail);new AppStore(context).statusIfCurrent(config,detail);}}
    }
    static void record(Context context,SettingsData config,RunGate.Ticket ticket,WifiTransport.Guard guard,Runnable action)throws IOException {
        synchronized(LOCK){synchronized(AppStore.LOCK){check(context,config,ticket);guard.check();action.run();}}
    }
    static void waiting(Context context,SettingsData config,RunGate.Ticket ticket,long delay){
        synchronized(LOCK){synchronized(AppStore.LOCK){try{check(context,config,ticket);ConnectionStatus.waitUntil=SystemClock.elapsedRealtime()+delay;report(context,config,ticket,"正在等待下一次网络复检");}catch(InterruptedIOException ignored){}}}
    }
    static void followup(Context context,SettingsData config,RunGate.Ticket ticket,long delay,int mode,String reason){
        synchronized(LOCK){
            if(ticket.cancelled)return;clearDeferred();deferredBackground=config.enabled;Context app=context.getApplicationContext();
            deferred=()->{synchronized(LOCK){deferred=null;AppStore store=new AppStore(app);if(store.isCurrent(config)&&(foreground||store.enabled()))request(app,mode,reason,null);}};
            main.postDelayed(deferred,Math.max(100,delay)+100);
        }
    }
    private static void clearDeferred(){if(deferred!=null)main.removeCallbacks(deferred);deferred=null;deferredBackground=false;}
    private static void interrupt(RunGate.Ticket ticket) {
        if(executing==ticket){WifiTransport transport=activeTransport;if(transport!=null)transport.cancel();Thread thread=activeThread;if(thread!=null)thread.interrupt();}
    }
    private static void discardQueued(){
        Runnable queued;
        while((queued=worker.getQueue().poll())!=null){
            RunGate.Ticket ticket=((QueuedCheck)queued).ticket;ticket.cancelled=true;
            List<Runnable> callbacks=completions.remove(ticket);
            if(callbacks!=null)for(Runnable callback:callbacks)main.post(callback);
        }
    }
    public static void cancel(){synchronized(LOCK){clearDeferred();SessionInfoRepository.pause();RunGate.Ticket old=gate.cancel();if(old!=null)interrupt(old);discardQueued();}}
    public static void cancelAutomatic(){synchronized(LOCK){RunGate.Ticket current=gate.current();if(current!=null&&current.mode==RunGate.AUTOMATIC)cancel();}}
}
