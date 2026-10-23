package cn.jieshen.oucautologin;

import android.content.Context;
import android.net.Network;
import android.os.SystemClock;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Native device page; no WebView, no cookies and no unmetered subresource requests. */
final class DeviceRepository {
    static final class Value {
        final String key,account,message;final long generation,fetchedAt;final boolean loading,complete;final List<SessionInfoClient.Device> devices;
        Value(String key,long generation,String account,String message,boolean loading,boolean complete,List<SessionInfoClient.Device> devices,long fetchedAt){this.key=key;this.generation=generation;this.account=account;this.message=message;this.loading=loading;this.complete=complete;this.devices=Collections.unmodifiableList(new ArrayList<>(devices));this.fetchedAt=fetchedAt;}
    }
    private static final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1));
    private static final java.util.concurrent.ScheduledExecutorService timer=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    private static volatile Value value=empty();private static volatile long token;private static WifiTransport active;private static Thread thread;
    private static long started;private static int next=1;private static SessionInfoClient client;
    private static Value empty(){return new Value("",-1,"","进入页面后读取在线设备",false,false,Collections.emptyList(),0);}
    static Value get(){return value;}
    static synchronized void pause(){token++;if(active!=null)active.cancel();if(thread!=null)thread.interrupt();worker.getQueue().clear();value=new Value(value.key,value.generation,value.account,"查询已暂停，可重试",false,value.complete,value.devices,value.fetchedAt);}
    static synchronized void invalidate(){pause();value=empty();started=0;client=null;next=1;}
    static synchronized void request(Context context,SettingsData config,Network network,String key,boolean refresh){
        long now=SystemClock.elapsedRealtime();boolean same=value.key.equals(key)&&value.generation==config.generation;
        if(value.loading)return;if(same&&now-started<10000)return;if(same&&refresh&&value.complete&&now-value.fetchedAt<30000)return;
        if(!same||refresh){invalidate();next=1;}
        long id=++token;started=now;Value old=value;value=new Value(key,config.generation,old.account,"正在读取设备",true,false,old.devices,old.fetchedAt);
        Context app=context.getApplicationContext();worker.execute(()->fetch(app,config,network,key,id));
    }
    private static void fetch(Context context,SettingsData config,Network network,String key,long id){
        Thread.interrupted();final SessionInfoClient session;final WifiTransport transport;final int start;
        synchronized(DeviceRepository.class){if(id!=token)return;thread=Thread.currentThread();active=new WifiTransport(network,SystemClock.elapsedRealtime()+8000,()->check(context,config,network,key,id),false);if(client==null)client=new SessionInfoClient(active);else client.transportForDevices(active);session=client;transport=active;start=next;}
        java.util.concurrent.ScheduledFuture<?> timeout=timer.schedule(transport::cancel,8,TimeUnit.SECONDS);
        try{
            SessionInfoClient.Page page=session.devices(start,SystemClock.elapsedRealtime());
            synchronized(DeviceRepository.class){check(context,config,network,key,id);Value old=value;
                if(!old.account.isEmpty()&&!old.account.equals(page.account))throw new SessionInfoClient.IdentityChanged();
                LinkedHashMap<String,SessionInfoClient.Device> rows=new LinkedHashMap<>();for(SessionInfoClient.Device d:old.devices)rows.put(d.id,d);int before=rows.size();for(SessionInfoClient.Device d:page.devices)rows.put(d.id,d);
                if(before==rows.size()&&!page.devices.isEmpty()&&next>1)throw new java.io.IOException("分页结果重复，尚未确认全部，请重新刷新");
                List<SessionInfoClient.Device> list=new ArrayList<>(rows.values());list.sort((a,b)->a.owner!=b.owner?(a.owner?-1:1):b.info.since.compareTo(a.info.since));next=page.next;
                value=new Value(key,config.generation,page.account,page.complete?(list.isEmpty()?"暂无在线设备":"已读取全部可见设备"):"尚未全部读取，可加载更多",false,page.complete,list,SystemClock.elapsedRealtime());
            }
        }catch(Exception e){synchronized(DeviceRepository.class){if(id==token){boolean changed=e instanceof SessionInfoClient.IdentityChanged;if(changed){next=1;client=null;}value=new Value(key,config.generation,changed?"":value.account,e instanceof java.io.IOException?e.getMessage():"设备信息暂不可用",false,false,changed?Collections.emptyList():value.devices,changed?0:value.fetchedAt);}}}
        finally{timeout.cancel(false);synchronized(DeviceRepository.class){if(id==token){active=null;thread=null;}}Thread.interrupted();}
    }
    private static void check(Context context,SettingsData config,Network network,String key,long id)throws InterruptedIOException{
        ConnectionStatus.Value c=ConnectionStatus.get();if(id!=token||Thread.currentThread().isInterrupted()||!new AppStore(context).isCurrent(config)||!ConnectionStatus.authenticated(c.state)||!c.key.equals(key)||!WifiAccess.matches(context,network,"",CheckCoordinator.foreground())||!WifiAccess.key(network).equals(key))throw new InterruptedIOException("设备查询已失效");
    }
}
