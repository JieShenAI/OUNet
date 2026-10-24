package cn.jieshen.oucautologin;

import android.content.Context;
import android.net.Network;
import android.os.SystemClock;
import java.io.IOException;
import java.io.InterruptedIOException;

/** Campus availability, authenticated identity and internet access are independent evidence. */
public final class LoginEngine {
    private static final CampusDetector campus=new CampusDetector();
    private static final ProbeScheduler probes=new ProbeScheduler();
    private static String evidenceKey="",proofAccount="",proofReason="";
    private static long evidenceGeneration=-1,firstOnline=-1,proofUntil=-1;
    private LoginEngine(){}
    static void check(Context context,SettingsData config,RunGate.Ticket ticket,long started)throws Exception {
        AppStore store=new AppStore(context);store.retry(); // One-time retirement of old cooldown/attempt data.
        WifiAccess.Snapshot snapshot=WifiAccess.snapshot(context,CheckCoordinator.foreground());
        WifiAccess.Link link=WifiAccess.actual(context,CheckCoordinator.foreground());
        if(link==null||snapshot.networkPermissionMissing){
            CheckCoordinator.publish(context,config,ticket,ConnectionStatus.State.INDETERMINATE,"",snapshot.networkPermissionMissing?"没有网络或位置权限":snapshot.links.isEmpty()?"尚未连接Wi-Fi":"无法唯一确认实际Wi-Fi，请检查双Wi-Fi或VPN设置");
            SessionInfoRepository.invalidate();DeviceRepository.invalidate();RecoveryRepository.get(context).connection("");return;
        }
        Network network=link.network;String key=WifiAccess.key(network);
        WifiTransport.Guard guard=()->{CheckCoordinator.check(context,config,ticket);if(!WifiAccess.matches(context,network,"",CheckCoordinator.foreground())||!WifiAccess.key(network).equals(key))throw new InterruptedIOException("实际Wi-Fi已变化");};
        WifiTransport transport=new WifiTransport(network,SystemClock.elapsedRealtime()+4000,guard);CheckCoordinator.transport(ticket,transport);
        if(!ticket.userReconnect&&store.recheckDelay()>0){
            CheckCoordinator.report(context,config,ticket,store.recheckMessage());
            CheckCoordinator.followup(context,config,ticket,store.recheckDelay(),config.enabled?ticket.mode:RunGate.READ_ONLY,"自动恢复复检");return;
        }
        CheckCoordinator.record(context,config,ticket,guard,()->{RecoveryRepository.get(context).connection(key);if(!key.equals(evidenceKey)||config.generation!=evidenceGeneration){evidenceKey=key;evidenceGeneration=config.generation;firstOnline=-1;proofUntil=-1;}});
        ConnectionStatus.Value previous=ConnectionStatus.get();
        SessionInfoRepository.Value identity=SessionInfoRepository.get();
        if(!key.equals(identity.key)||identity.generation!=config.generation){SessionInfoRepository.invalidate();DeviceRepository.invalidate();}
        // Monitoring asks the question the user cares about first. A working internet
        // connection needs neither portal classification nor a fresh XHA identity.
        // Explicit reconnect is the user's request to refresh that identity instead.
        if(!ticket.userReconnect){
            ProbeScheduler.Result probe;
            transport.deadline(SystemClock.elapsedRealtime()+2500);
            try{probe=probes.probe(key,SystemClock.elapsedRealtime(),true,link.captive,transport,RequestControl.GLOBAL);}
            catch(InterruptedIOException e){guard.check();probe=new ProbeScheduler.Result(ProbeScheduler.State.UNKNOWN,0);}
            guard.check();if(probe.fresh)store.checked();
            if(probe.state==ProbeScheduler.State.ONLINE){online(context,config,ticket,key,guard,probe.fresh);return;}
            if(probe.state!=ProbeScheduler.State.OFFLINE){
                CheckCoordinator.publish(context,config,ticket,ConnectionStatus.State.INDETERMINATE,key,"正在复核外网连通性");
                CheckCoordinator.followup(context,config,ticket,probe.state==ProbeScheduler.State.WAIT?probe.waitMs:300,ticket.mode,"外网复检");return;
            }
            transport.deadline(SystemClock.elapsedRealtime()+4000);
        }
        AuthError credentialError=store.authError(config);
        if(credentialError!=AuthError.NONE&&!ticket.userReconnect){
            CheckCoordinator.publish(context,config,ticket,ConnectionStatus.State.CREDENTIAL_ERROR,key,credentialError.guidance());
            SessionInfoRepository.invalidate();DeviceRepository.invalidate();return;
        }
        if(ticket.userReconnect||!key.equals(previous.key)||previous.generation!=config.generation)CheckCoordinator.publish(context,config,ticket,ConnectionStatus.State.CHECKING,key,"正在请求XHA认证服务");
        CampusDetector.Result classification;
        try{classification=campus.detect(key,SystemClock.elapsedRealtime(),ticket.mode==RunGate.MANUAL,ticket.userReconnect,transport);}
        catch(RequestControl.Limited e){waitFor(context,config,ticket,e.delayMs);return;}
        catch(InterruptedIOException e){guard.check();campus.timeout(key,SystemClock.elapsedRealtime());classification=new CampusDetector.Result(false,"XHA访问超时");}
        guard.check();
        if(!classification.campus){
            CheckCoordinator.record(context,config,ticket,guard,()->{try{store.recordTransportFailure(config);}catch(IOException e){throw new IllegalStateException(e);}});
            CheckCoordinator.publish(context,config,ticket,ConnectionStatus.State.NON_CAMPUS,key,classification.detail);SessionInfoRepository.invalidate();DeviceRepository.invalidate();
            CheckCoordinator.record(context,config,ticket,guard,()->{RecoveryRepository.get(context).connection("");firstOnline=-1;proofUntil=-1;});return;
        }
        CheckCoordinator.report(context,config,ticket,"XHA认证服务可用，正在读取登录状态");
        transport.deadline(SystemClock.elapsedRealtime()+8000);SessionInfoClient client=new SessionInfoClient(transport);SessionInfoClient.Status session;
        try{session=client.readStatus();}
        catch(IOException e){guard.check();store.recordTransportFailure(config);unknown(context,config,ticket,key,"登录状态暂无法确认："+e.getMessage());return;}
        guard.check();
        if(session.auth==SessionInfoClient.Auth.UNKNOWN){store.recordTransportFailure(config);unknown(context,config,ticket,key,"登录状态暂无法确认：XHA状态结果未识别");return;}
        store.resetTransportRetry(config);
        if(session.auth==SessionInfoClient.Auth.ONLINE){present(context,config,ticket,link,key,guard,transport,client,session,ticket.userReconnect);return;}
        SessionInfoRepository.invalidate();DeviceRepository.invalidate();
        CheckCoordinator.publish(context,config,ticket,ConnectionStatus.State.OFFLINE,key,"XHA确认未登录");
        CheckCoordinator.record(context,config,ticket,guard,()->{firstOnline=-1;RecoveryRepository.get(context).offline(key,link.ssid.isEmpty()?"Wi-Fi名称不可见":link.ssid);});
        if(ticket.mode==RunGate.READ_ONLY||(ticket.mode==RunGate.AUTOMATIC&&!config.enabled))return;
        if(config.active()==null){CheckCoordinator.report(context,config,ticket,"XHA未登录，请添加并选用账号");return;}
        if(!store.retry().canAttempt(System.currentTimeMillis())){CheckCoordinator.report(context,config,ticket,store.recheckMessage());return;}
        String password;
        try{password=store.password(config.active());}catch(Exception e){CheckCoordinator.report(context,config,ticket,"无法读取密码，请重新保存账号密码");return;}
        SessionInfoRepository.pause();DeviceRepository.pause();RequestControl.GLOBAL.authentication(true);
        try{
            guard.check();CheckCoordinator.record(context,config,ticket,guard,()->RecoveryRepository.get(context).attempt(key));
            CheckCoordinator.report(context,config,ticket,"XHA确认未登录，开始认证");
            transport.deadline(Math.min(started+45000,SystemClock.elapsedRealtime()+30000));
            try{
                PortalClient.AuthenticationResult result=new PortalClient(transport).authenticate(config.active().username,password,session.context);
                guard.check();CheckCoordinator.report(context,config,ticket,result.message);
                if(result.accepted)CheckCoordinator.record(context,config,ticket,guard,()->{proofUntil=SystemClock.elapsedRealtime()+60000;proofAccount=config.active().username;proofReason=ticket.reason;});
                if(result.accepted)CheckCoordinator.record(context,config,ticket,guard,()->reset(store,config));
                else if(result.refused){
                    synchronized(AppStore.LOCK){guard.check();store.recordAuthFailure(config,System.currentTimeMillis());if(result.error!=AuthError.NONE)store.rememberAuthError(config,result.error);}
                    if(result.error!=AuthError.NONE){
                        CheckCoordinator.publish(context,config,ticket,ConnectionStatus.State.CREDENTIAL_ERROR,key,result.error.guidance());return;
                    }
                }else store.recordAuthFailure(config,System.currentTimeMillis());
            }catch(InterruptedIOException e){guard.check();store.recordAuthFailure(config,System.currentTimeMillis());CheckCoordinator.report(context,config,ticket,"认证请求超时，将自动复检");}
            catch(IOException e){guard.check();store.recordAuthFailure(config,System.currentTimeMillis());CheckCoordinator.report(context,config,ticket,"认证结果暂无法确认，将自动复检："+e.getMessage());}
            probes.afterAuthentication();
            transport.deadline(SystemClock.elapsedRealtime()+8000);
            try{
                SessionInfoClient.Status refreshed=client.readStatus();guard.check();
                if(refreshed.auth==SessionInfoClient.Auth.ONLINE)present(context,config,ticket,link,key,guard,transport,client,refreshed,true);
                else if(refreshed.auth==SessionInfoClient.Auth.UNAUTHENTICATED)CheckCoordinator.publish(context,config,ticket,ConnectionStatus.State.OFFLINE,key,store.retry().blocked?store.recheckMessage():"XHA仍未登录，将继续检测");
                else unknown(context,config,ticket,key,"认证后登录状态暂无法确认：XHA状态结果未识别");
            }catch(IOException e){guard.check();unknown(context,config,ticket,key,"认证后登录状态暂无法确认："+e.getMessage());}
        }finally{RequestControl.GLOBAL.authentication(false);}
    }
    private static void reset(AppStore store,SettingsData config){
        try{store.resetRetry(config);}catch(IOException e){throw new IllegalStateException(e);}
    }
    private static void unknown(Context context,SettingsData config,RunGate.Ticket ticket,String key,String message)throws IOException{
        CheckCoordinator.publish(context,config,ticket,ConnectionStatus.State.INDETERMINATE,key,message);
        // History stays timestamped in memory; it never authorizes authentication.
        SessionInfoRepository.pause();DeviceRepository.invalidate();
    }
    private static void present(Context context,SettingsData config,RunGate.Ticket ticket,WifiAccess.Link link,String key,WifiTransport.Guard guard,WifiTransport transport,SessionInfoClient client,SessionInfoClient.Status session,boolean force)throws Exception{
        AppStore store=new AppStore(context);
        CheckCoordinator.record(context,config,ticket,guard,()->reset(store,config));
        CheckCoordinator.publish(context,config,ticket,ConnectionStatus.State.SIGNED_IN,key,"XHA已登录，正在确认外网");
        SessionInfoRepository.Value old=SessionInfoRepository.get();
        if(old.info!=null&&!old.info.account.equals(session.account))DeviceRepository.invalidate();
        SessionInfoClient.Info info=force?null:SessionInfoRepository.cached(config,key,session.account);
        boolean reused=info!=null;
        boolean complete=info!=null;String message="";
        if(info==null){
            info=session.summary();
            if(session.account.isEmpty())message="XHA已在线，但未提供账号；不重复登录";
            else{
                transport.deadline(SystemClock.elapsedRealtime()+8000);
                try{info=client.fetch(session);complete=true;}
                catch(IOException e){guard.check();message="在线身份已确认，详情读取失败："+e.getMessage();}
            }
        }
        // A cached automatic evaluation must not cancel a user's in-flight detail refresh.
        if(!reused)SessionInfoRepository.confirmed(config,key,info,message,complete,guard);
        CheckCoordinator.report(context,config,ticket,complete?"XHA在线，账号信息已确认":message);
        transport.deadline(SystemClock.elapsedRealtime()+2500);
        boolean confirm=firstOnline>=0&&SystemClock.elapsedRealtime()-firstOnline>=2000&&RecoveryRepository.get(context).hasOutage(key);
        ProbeScheduler.Result probe;
        try{probe=probes.probe(key,SystemClock.elapsedRealtime(),force||confirm||ticket.mode==RunGate.MANUAL,link.captive,transport,RequestControl.GLOBAL);}
        catch(InterruptedIOException e){guard.check();CheckCoordinator.report(context,config,ticket,"XHA已登录，外网探测超时");return;}
        guard.check();if(probe.fresh)store.checked();
        if(probe.state==ProbeScheduler.State.ONLINE){online(context,config,ticket,key,guard,probe.fresh);return;}
        CheckCoordinator.publish(context,config,ticket,ConnectionStatus.State.SIGNED_IN,key,probe.state==ProbeScheduler.State.OFFLINE?"XHA已登录，外网暂不可用":"XHA已登录，外网正在确认");
        if(probe.state==ProbeScheduler.State.OFFLINE)CheckCoordinator.record(context,config,ticket,guard,()->{firstOnline=-1;RecoveryRepository.get(context).offline(key,link.ssid.isEmpty()?"Wi-Fi名称不可见":link.ssid);});
        else CheckCoordinator.followup(context,config,ticket,probe.state==ProbeScheduler.State.WAIT?probe.waitMs:300,RunGate.READ_ONLY,"外网复检");
    }
    private static void waitFor(Context context,SettingsData config,RunGate.Ticket ticket,long delay){
        CheckCoordinator.waiting(context,config,ticket,delay);CheckCoordinator.followup(context,config,ticket,delay,RunGate.READ_ONLY,"连接复检");
    }
    private static void online(Context context,SettingsData config,RunGate.Ticket ticket,String key,WifiTransport.Guard guard,boolean fresh)throws Exception{
        guard.check();CheckCoordinator.publish(context,config,ticket,ConnectionStatus.State.ONLINE,key,"外网验证通过");
        CheckCoordinator.record(context,config,ticket,guard,()->{try{new AppStore(context).connectionRecovered(config);}catch(IOException e){throw new IllegalStateException(e);}});
        if(!RecoveryRepository.get(context).hasOutage(key))return;
        long now=SystemClock.elapsedRealtime();
        if(fresh&&firstOnline>=0&&now-firstOnline>=2000){
            CheckCoordinator.record(context,config,ticket,guard,()->RecoveryRepository.get(context).recovered(key,proofUntil>=now,proofUntil>=now?proofAccount:"",proofUntil>=now?proofReason:ticket.reason));firstOnline=-1;proofUntil=-1;
        }else{if(fresh&&firstOnline<0)firstOnline=now;CheckCoordinator.followup(context,config,ticket,firstOnline<0?2000:Math.max(300,2000-(now-firstOnline)),RunGate.READ_ONLY,"稳定复检");}
    }
}
