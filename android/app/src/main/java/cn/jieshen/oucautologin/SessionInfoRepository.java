package cn.jieshen.oucautologin;

import android.content.Context;
import android.net.Network;
import android.os.SystemClock;
import java.io.InterruptedIOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Low-priority read-only work, independent of authentication and its attempt budget. */
final class SessionInfoRepository {
    static final class Value {
        final String key,message;final long generation;final boolean loading,complete;final SessionInfoClient.Info info;
        Value(String key,long generation,boolean loading,SessionInfoClient.Info info,String message){this(key,generation,loading,info,message,info!=null);}
        Value(String key,long generation,boolean loading,SessionInfoClient.Info info,String message,boolean complete){this.key=key;this.generation=generation;this.loading=loading;this.info=info;this.message=message;this.complete=complete;}
    }
    private static final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1));
    private static final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    private static volatile Value value=new Value("",-1,false,null,"会话信息暂不可用");
    private static volatile long sequence;
    private static long lastStarted;
    private static WifiTransport active;
    private static Thread activeThread;
    static Value get(){return value;}
    static SessionInfoClient.Info cached(SettingsData c,String key,String account){Value v=value;return v.complete&&v.info!=null&&v.key.equals(key)&&v.generation==c.generation&&v.info.account.equals(account)&&System.currentTimeMillis()-v.info.fetchedAt>=0&&System.currentTimeMillis()-v.info.fetchedAt<300000?v.info:null;}
    static synchronized void confirmed(SettingsData c,String key,SessionInfoClient.Info info,String message,boolean complete,WifiTransport.Guard guard)throws java.io.IOException{
        guard.check();pause();value=new Value(key,c.generation,false,info,message,complete);
    }
    static synchronized void pause(){sequence++;if(active!=null)active.cancel();if(activeThread!=null)activeThread.interrupt();worker.getQueue().clear();value=new Value(value.key,value.generation,false,value.info,value.message,value.complete);}
    static synchronized void invalidate(){pause();lastStarted=0;value=new Value("",-1,false,null,"会话信息暂不可用");}
    static synchronized void request(Context context,SettingsData config,Network network,String key,boolean force){
        long now=SystemClock.elapsedRealtime();Value old=value;
        boolean same=old.key.equals(key)&&old.generation==config.generation;
        if(!force&&same&&(old.loading||now-lastStarted<10000||(old.complete&&old.info!=null&&System.currentTimeMillis()-old.info.fetchedAt<300000)))return;
        pause();final long token=++sequence;lastStarted=now;
        value=new Value(key,config.generation,true,same?old.info:null,"正在读取学校会话");
        Context app=context.getApplicationContext();
        worker.execute(()->fetch(app,config,network,key,token));
    }
    private static void fetch(Context context,SettingsData config,Network network,String key,long token){
        Thread.interrupted();WifiTransport transport=new WifiTransport(network,SystemClock.elapsedRealtime()+8000,()->check(context,config,network,key,token),false);
        synchronized(SessionInfoRepository.class){if(token!=sequence)return;active=transport;activeThread=Thread.currentThread();}
        java.util.concurrent.ScheduledFuture<?> timeout=timer.schedule(()->{synchronized(SessionInfoRepository.class){if(token==sequence){transport.cancel();if(activeThread!=null)activeThread.interrupt();}}},8,TimeUnit.SECONDS);
        try{
            SessionInfoClient.Info info=new SessionInfoClient(transport).fetch();
            synchronized(SessionInfoRepository.class){check(context,config,network,key,token);if(value.info!=null&&!value.info.account.equals(info.account))DeviceRepository.invalidate();value=new Value(key,config.generation,false,info,"");}
        }catch(Exception e){
            synchronized(SessionInfoRepository.class){if(token==sequence)value=new Value(key,config.generation,false,value.info,value.info==null?"会话信息暂不可用，可单独重试":"上次读取，刷新失败，可单独重试",false);}
        }finally{
            timeout.cancel(false);synchronized(SessionInfoRepository.class){if(token==sequence){active=null;activeThread=null;}}Thread.interrupted();
        }
    }
    private static void check(Context context,SettingsData config,Network network,String key,long token)throws InterruptedIOException {
        ConnectionStatus.Value connection=ConnectionStatus.get();
        if(token!=sequence||Thread.currentThread().isInterrupted()||!new AppStore(context).isCurrent(config)||!ConnectionStatus.authenticated(connection.state)||!connection.key.equals(key)||!WifiAccess.matches(context,network,"",CheckCoordinator.foreground())||!WifiAccess.key(network).equals(key))throw new InterruptedIOException("会话查询已失效");
    }
}
