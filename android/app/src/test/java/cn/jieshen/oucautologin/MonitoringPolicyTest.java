package cn.jieshen.oucautologin;

import java.io.IOException;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class MonitoringPolicyTest {
    static final String ROOT="<html><link href='b80.css'><link href='b82.css'><script src='a41.js?version=1608022758403'></script></html>";
    @Test public void rootContractMatchesObservedPublicTemplateAndRejectsGenericPages(){
        assertTrue(CampusDetector.portalPage(ROOT));assertTrue(CampusDetector.portalPage("<html>var v46ip='';<script src='/a41.js'></script></html>"));
        for(String bad:new String[]{"<html>Success</html>","a41.js b80.css b82.css","<html><script src='https://evil.example/a41.js'></script>b80.css b82.css</html>"})assertFalse(CampusDetector.portalPage(bad));
    }
    @Test public void threeHundredMillisecondTicksDoNotRepeatedlyQueryXha()throws Exception{
        CampusDetector d=new CampusDetector();AtomicInteger requests=new AtomicInteger();PortalClient.Transport t=url->{requests.incrementAndGet();assertEquals(PortalClient.HOST,url);return new PortalClient.Response(200,ROOT);};
        for(long now=0;now<60000;now+=300)assertTrue(d.detect("wifi:1",now,false,t).campus);assertEquals(1,requests.get());d.detect("wifi:1",60000,false,t);assertEquals(2,requests.get());d.detect("wifi:2",60300,false,t);assertEquals(3,requests.get());
    }
    @Test public void negativeCampusEvidenceRetriesAtMinimumTickAndManualBypassesCache()throws Exception{
        CampusDetector d=new CampusDetector();AtomicInteger calls=new AtomicInteger();PortalClient.Transport fail=url->{calls.incrementAndGet();throw new IOException();};
        assertFalse(d.detect("n",0,false,fail).campus);d.detect("n",299,false,fail);assertEquals(1,calls.get());d.detect("n",300,false,fail);assertEquals(2,calls.get());
        assertTrue(d.detect("n",301,true,url->new PortalClient.Response(200,LOGIN_FORM)).campus);
        for(long now=1000;now<100000;now+=300){d.timeout("n",now);assertTrue(d.nextDelay(now)<=300);}
        CampusDetector valid=new CampusDetector();valid.detect("n",0,false,url->new PortalClient.Response(200,ROOT));
        try{valid.detect("n",10001,true,url->{throw new RequestControl.Limited(5000);});fail();}catch(RequestControl.Limited expected){}
        assertTrue(valid.detect("n",11000,false,fail).campus);
    }
    @Test public void freshAuthenticationEvidenceCannotUseSixtySecondCache()throws Exception{
        CampusDetector d=new CampusDetector();AtomicInteger n=new AtomicInteger();PortalClient.Transport t=url->{n.incrementAndGet();return new PortalClient.Response(200,ROOT);};
        d.detect("n",0,false,t);d.detect("n",9999,true,t);assertEquals(1,n.get());d.detect("n",10000,true,t);assertEquals(2,n.get());
    }
    @Test public void userReconnectBypassesEvenOneMillisecondOldPositiveCampusCache()throws Exception{
        CampusDetector d=new CampusDetector();AtomicInteger n=new AtomicInteger();PortalClient.Transport t=url->{n.incrementAndGet();return new PortalClient.Response(200,ROOT);};
        d.detect("n",0,false,t);d.detect("n",1,true,true,t);assertEquals(2,n.get());
    }
    @Test public void requestWindowExpiresAtExactBoundaryAndRetainsLaunchTotal(){RollingWindow w=new RollingWindow();w.add(0);w.add(1000);assertEquals(2,w.count(59999));assertEquals(1,w.count(60000));assertEquals(0,w.count(61000));assertEquals(2,w.total());}
    @Test public void schoolTrafficHasNoMinuteQuotaButKeepsAccurateCounters()throws Exception{
        AtomicLong clock=new AtomicLong();RequestControl c=new RequestControl(clock::get);URL xha=new URL("https://xha.ouc.edu.cn:802/eportal/portal/page/loadOnlineRecord");
        for(int i=0;i<200;i++)c.dispatched(xha,i%2==0);assertEquals(200,c.total());assertEquals(200,c.minute());assertEquals(0,c.delay(xha,true));
        c.authentication(true);assertEquals(0,c.delay(xha,true));assertEquals(300,c.delay(xha,false));c.authentication(false);assertEquals(0,c.delay(xha,false));
        clock.set(60000);assertEquals(0,c.minute());assertEquals(200,c.total());assertEquals(0,c.delay(xha,true));
    }
    @Test public void concurrentSchoolDispatchesAllCountWithoutOldSixtyRequestCap()throws Exception{
        RequestControl c=new RequestControl(()->0L);URL root=new URL(PortalClient.HOST);java.util.List<Thread> threads=new java.util.ArrayList<>();
        for(int i=0;i<100;i++){Thread t=new Thread(()->{try{c.dispatched(root,true);}catch(IOException expected){}});threads.add(t);t.start();}for(Thread t:threads)t.join();assertEquals(100,c.total());
    }
    @Test public void providerRotationAllowsImmediateFreshChecksAfterAuthentication()throws Exception{
        AtomicLong clock=new AtomicLong();RequestControl c=new RequestControl(clock::get);ProbeScheduler p=new ProbeScheduler();java.util.List<String> seen=new java.util.ArrayList<>();
        PortalClient.Transport t=url->{c.dispatched(new URL(url),true);seen.add(url);return new PortalClient.Response(200,url.contains("apple")?"<title>Success</title><body>Success</body>":url.contains("firefox")?"success\n":"Microsoft Connect Test");};
        assertTrue(p.probe("n",0,false,false,t,c).fresh);assertFalse(p.probe("n",300,false,false,t,c).fresh);assertEquals(1,seen.size());
        clock.set(2000);assertTrue(p.probe("n",2000,true,false,t,c).fresh);clock.set(4000);assertTrue(p.probe("n",4000,true,false,t,c).fresh);clock.set(6000);p.afterAuthentication();assertEquals(ProbeScheduler.State.ONLINE,p.probe("n",6000,false,false,t,c).state);assertEquals(4,seen.size());assertEquals(seen.get(0),seen.get(3));
    }
    @Test public void tenMinutesOfFastTicksKeepsRequestsBounded()throws Exception{
        AtomicLong clock=new AtomicLong();RequestControl c=new RequestControl(clock::get);ProbeScheduler p=new ProbeScheduler();AtomicInteger requests=new AtomicInteger();
        PortalClient.Transport t=url->{c.dispatched(new URL(url),true);requests.incrementAndGet();return new PortalClient.Response(200,url.contains("apple")?"<title>Success</title><body>Success</body>":url.contains("firefox")?"success":"Microsoft Connect Test");};
        for(long n=0;n<600000;n+=300){clock.set(n);p.probe("n",n,false,false,t,c);assertTrue(c.probeMinute()<=20);}assertTrue(requests.get()<=41);assertEquals(0,c.total());
    }
    @Test public void twoIndependentFailuresAreRequiredWithoutCaptiveEvidence()throws Exception{
        AtomicLong clock=new AtomicLong();RequestControl c=new RequestControl(clock::get);ProbeScheduler p=new ProbeScheduler();PortalClient.Transport t=url->{c.dispatched(new URL(url),true);return new PortalClient.Response(503,"");};
        assertEquals(ProbeScheduler.State.UNKNOWN,p.probe("n",0,false,false,t,c).state);clock.set(1000);assertEquals(ProbeScheduler.State.OFFLINE,p.probe("n",1000,false,false,t,c).state);
        assertEquals(ProbeScheduler.State.OFFLINE,new ProbeScheduler().probe("other",1000,false,true,url->new PortalClient.Response(302,""),new RequestControl(clock::get)).state);
    }
    @Test public void decimalSecondsAreExactAndNoCatchupQueue(){
        assertEquals(300,SettingsData.parseSeconds("0.3"));assertEquals(1000,SettingsData.parseSeconds("1"));assertEquals(1250,SettingsData.parseSeconds("1.25"));
        for(String bad:new String[]{"0.299","0.0003","-1","NaN","3600.001","1.0001"})try{SettingsData.parseSeconds(bad);fail(bad);}catch(IllegalArgumentException expected){}
        assertEquals(10300,CheckSchedule.nextMillis(1000,10000,300));assertEquals(1300,CheckSchedule.nextMillis(1000,1100,300));
    }
    static final String LOGIN_FORM="<form method='post'><input name='DDDDD'><input name='upass' type='password'><button>登录</button></form>";
    @Test public void accountAndPasswordInputsAreCampusWithoutVersionSpecificAssets(){
        for(String page:new String[]{LOGIN_FORM,"<INPUT placeholder=账号 type=text><INPUT TYPE=PASSWORD placeholder=密码>","<form><input type='email' name='username'><input autocomplete='current-password' type='password'></form>","<form><input title='a > b' name=user_account><input type=password name=user_password></form>"})assertTrue(page,CampusDetector.portalPage(page));
    }
    @Test public void InertOrIncompleteLoginMarkupDoesNotProveCampus(){
        for(String page:new String[]{"<!--"+LOGIN_FORM+"-->","<script>const template=\""+LOGIN_FORM+"\";</script>","<template>"+LOGIN_FORM+"</template>","<input type=hidden name=account><input type=password>","<input disabled name=username><input type=password>","<input name=username><input type=password disabled>","<form><input name=username></form><form><input type=password></form>","<input name=username>","<input type=password>"})assertFalse(page,CampusDetector.portalPage(page));
    }
    @Test public void repeatedOfflineTicksNeverEnterLongBackoffOrMinuteQuota()throws Exception{
        AtomicLong clock=new AtomicLong();RequestControl c=new RequestControl(clock::get);ProbeScheduler p=new ProbeScheduler();AtomicInteger n=new AtomicInteger();
        PortalClient.Transport fail=url->{assertEquals(ProbeScheduler.URLS[n.get()%3],url);c.dispatched(new URL(url),true);n.incrementAndGet();throw new IOException("offline");};
        for(long now=0;now<180000;now+=300){clock.set(now);ProbeScheduler.Result r=p.probe("n",now,false,false,fail,c);assertEquals(now==0?ProbeScheduler.State.UNKNOWN:ProbeScheduler.State.OFFLINE,r.state);assertTrue(r.fresh);}
        assertEquals(600,n.get());assertEquals(200,c.probeMinute());
    }
    @Test public void onlySubTickProbeBurstsWaitAndNextTickAlwaysDispatches()throws Exception{
        AtomicLong clock=new AtomicLong();RequestControl c=new RequestControl(clock::get);URL probe=new URL(ProbeScheduler.URLS[0]);c.dispatched(probe,true);
        clock.set(1);assertEquals(299,c.delay(new URL(ProbeScheduler.URLS[1]),true));clock.set(300);c.dispatched(probe,true);assertEquals(2,c.probeMinute());
    }
}
