package cn.jieshen.oucautologin;

import android.app.Application;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStoreSpi;
import java.security.Provider;
import java.security.Security;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,shadows=StartupFlowTest.StubNetwork.class)
public class StartupFlowTest {
    private static final List<String> calls=Collections.synchronizedList(new ArrayList<>());
    private static volatile boolean campus,internet;private static volatile String rootBody;private static int networkId=100;private static long clockFloor;
    private Context context;private AppStore store;
    private static volatile int serverStatus,loginResult;
    private static volatile String serverAccount;
    private static volatile String loginMessage;
    private static volatile boolean detailsFail;
    private static volatile java.util.concurrent.CountDownLatch rootEntered,rootRelease;
    @Implements(Network.class)
    public static class StubNetwork extends ShadowNetwork {
        @Implementation protected URLConnection openConnection(URL url,Proxy proxy){
            return new HttpURLConnection(url){
                public void disconnect(){}public boolean usingProxy(){return false;}public void connect(){}
                public int getResponseCode(){calls.add(url.toString());if(url.getHost().equals("xha.ouc.edu.cn")&&url.getPath().equals("/")&&rootEntered!=null){rootEntered.countDown();try{rootRelease.await(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}return detailsFail&&url.getPath().endsWith("loadOnlineRecord")?503:200;}
                public String getContentType(){return "text/plain; charset=UTF-8";}
                public InputStream getInputStream(){String path=url.getPath(),body="";
                    if(url.getHost().equals("xha.ouc.edu.cn")){
                        if(path.equals("/"))body=campus?rootBody:"<html>Not the portal</html>";
                        else if(path.equals("/a79.htm"))body="v46ip='192.0.2.23';ss4='020000000023';";
                        else if(path.equals("/a41.js"))body="var enHTTPSPort=802;var page={name:'test_program'};";
                        else if(path.endsWith("loadConfig"))body="{\"data\":{\"login_method\":1,\"check_online_method\":0,\"page_index\":\"test_page\"}}";
                        else if(path.endsWith("/login")){if(loginResult==1)serverStatus=1;body="{\"result\":"+loginResult+",\"msg\":"+org.json.JSONObject.quote(loginMessage)+"}";}
                        else if(path.endsWith("chkstatus"))body="{\"result\":"+serverStatus+",\"uid\":\""+serverAccount+"\",\"v46ip\":\"192.0.2.23\",\"ss4\":\"020000000023\"}";
                        else if(path.endsWith("loadOnlineRecord"))body="{\"records\":[{\"is_owner_ip\":1,\"uid\":\""+serverAccount+"\",\"login_ip\":\"192.0.2.23\",\"mac_address\":\"020000000023\",\"login_time\":\"2026-10-23 10:00:00\",\"phone_flag\":1}]}";
                    }else if(internet)body=url.getHost().contains("apple")?"<title>Success</title><body>Success</body>":url.getHost().contains("firefox")?"success":"Microsoft Connect Test";
                    return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
                }
            };
        }
    }
    // Test-only keystore provider: exercise real AppStore AES/GCM and production LoginEngine.
    public static class TestKeys extends KeyStoreSpi {
        public Key engineGetKey(String a,char[] p){return new SecretKeySpec("1234567890abcdef".getBytes(StandardCharsets.UTF_8),"AES");}
        public boolean engineContainsAlias(String a){return true;}public boolean engineIsKeyEntry(String a){return true;}public boolean engineIsCertificateEntry(String a){return false;}
        public Certificate[] engineGetCertificateChain(String a){return null;}public Certificate engineGetCertificate(String a){return null;}public Date engineGetCreationDate(String a){return new Date(0);}
        public void engineSetKeyEntry(String a,Key k,char[] p,Certificate[] c){}public void engineSetKeyEntry(String a,byte[] k,Certificate[] c){}public void engineSetCertificateEntry(String a,Certificate c){}public void engineDeleteEntry(String a){}
        public Enumeration<String> engineAliases(){return Collections.enumeration(Collections.singleton("ouc_portal_password_v1"));}public int engineSize(){return 1;}public String engineGetCertificateAlias(Certificate c){return null;}
        public void engineStore(java.io.OutputStream s,char[] p){}public void engineLoad(InputStream s,char[] p){}
    }
    @Before public void setup()throws Exception{
        // Keep process-wide meters monotonic even after a test simulates ten minutes.
        // Robolectric resets its clock between tests, while those meters survive.
        ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(Math.max(61000,clockFloor+61000-android.os.SystemClock.elapsedRealtime())));CheckCoordinator.cancel();CheckCoordinator.foreground(true);calls.clear();campus=true;internet=false;rootBody=MonitoringPolicyTest.ROOT;
        serverStatus=0;loginResult=1;serverAccount="actual-user";detailsFail=false;rootEntered=null;rootRelease=null;
        loginMessage="test rejection";
        context=RuntimeEnvironment.getApplication();context.getSharedPreferences("ouc_local",0).edit().clear().commit();store=new AppStore(context);
        // Normal manifest permissions are install-time grants on a real device.
        Shadows.shadowOf((Application)context).grantPermissions(android.Manifest.permission.INTERNET,android.Manifest.permission.ACCESS_NETWORK_STATE,android.Manifest.permission.ACCESS_WIFI_STATE);
        new java.io.File(context.getFilesDir(),"events.log").delete();
        Provider provider=new Provider("TestAndroidKS",1.0,"Unit test provider"){};provider.put("KeyStore.AndroidKeyStore",TestKeys.class.getName());Security.addProvider(provider);
        ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);Shadows.shadowOf(cm).clearAllNetworks();Network n=ShadowNetwork.newInstance(++networkId);
        NetworkInfo info=org.robolectric.shadows.ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,ConnectivityManager.TYPE_WIFI,0,true,true);Shadows.shadowOf(cm).addNetwork(n,info);Shadows.shadowOf(cm).setNetworkCapabilities(n,wifiCapabilities());
    }
    private NetworkCapabilities wifiCapabilities(){NetworkCapabilities c=new NetworkCapabilities();Shadows.shadowOf(c).addTransportType(NetworkCapabilities.TRANSPORT_WIFI);Shadows.shadowOf(c).addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);return c;}
    @After public void stop(){CheckCoordinator.cancel();CheckCoordinator.foreground(false);Security.removeProvider("TestAndroidKS");clockFloor=android.os.SystemClock.elapsedRealtime();}
    private void runCheck()throws Exception{runCheck(RunGate.MANUAL);}
    private void runCheck(int mode)throws Exception{ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(300));CheckCoordinator.request(context,mode,"启动连接",null);long deadline=System.currentTimeMillis()+5000;while(CheckCoordinator.running()&&System.currentTimeMillis()<deadline)Thread.sleep(10);assertFalse("worker did not finish",CheckCoordinator.running());}
    @Test public void offlineNonCampusStartupProbesInternetBeforeXhaWithoutSubmittingCredentials()throws Exception{
        campus=false;runCheck();assertEquals(2,calls.size());assertEquals(ProbeScheduler.URLS[0],calls.get(0));assertEquals(PortalClient.HOST,calls.get(1));assertEquals(ConnectionStatus.State.NON_CAMPUS,ConnectionStatus.get().state);assertTrue(store.retry().failures.isEmpty());
    }
    @Test public void startupAuthenticatesSavedAccountWithoutTargetOrBackgroundSwitch()throws Exception{
        String id=store.saveAccount(null,"test","test-student","test-pass");store.selectAccount(id);assertFalse(store.enabled());assertEquals("",store.ssid());runCheck();
        assertEquals(ProbeScheduler.URLS[0],calls.get(0));assertEquals(PortalClient.HOST,calls.get(1));assertTrue(calls.stream().anyMatch(u->u.contains("/login?")));assertEquals(0,store.retry().failures.size());
        assertTrue(calls.stream().filter(u->u.contains("/login?")).allMatch(u->u.contains("test-student")&&u.contains("terminal_type=2")));assertFalse(store.enabled());
    }
    @Test public void savedAccountCannotBypassPauseAfterRestartOrSwitch()throws Exception{
        String id=store.saveAccount(null,"test","test-student","test-pass");store.selectAccount(id);long now=System.currentTimeMillis();store.recordAuthFailure(store.config(),now-130000);store.recordAuthFailure(store.config(),now-65000);store.recordAuthFailure(store.config(),now);runCheck();
        assertTrue(store.retry().blocked);assertFalse(calls.stream().anyMatch(u->u.contains("/login?")));assertEquals(PortalClient.HOST,calls.get(1));
    }
    @Test public void cellularDefaultCannotMaskTheOnlyCaptiveWifi()throws Exception {
        ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);Network expected=WifiAccess.actual(context,true).network;
        org.robolectric.Shadows.shadowOf(manager).setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,ConnectivityManager.TYPE_MOBILE,0,true,true));
        assertEquals(expected,WifiAccess.actual(context,true).network);campus=false;runCheck();assertEquals(PortalClient.HOST,calls.get(1));assertEquals(ConnectionStatus.State.NON_CAMPUS,ConnectionStatus.get().state);
    }
    @Test public void unauthenticatedLoginFormTriggersSavedAccountLoginDespitePreviousXhaTraffic()throws Exception{
        rootBody=MonitoringPolicyTest.LOGIN_FORM;String id=store.saveAccount(null,"test","test-student","test-pass");store.selectAccount(id);
        for(int i=0;i<80;i++)RequestControl.GLOBAL.dispatched(new URL(PortalClient.HOST),true);
        runCheck();assertTrue(calls.stream().anyMatch(u->u.contains("/login?")));assertNotEquals(ConnectionStatus.State.NON_CAMPUS,ConnectionStatus.get().state);assertEquals(0,store.retry().failures.size());
    }
    @Test public void manualRetrySeesAvailableLoginFormWithoutWaitingForNegativeCache()throws Exception{
        campus=false;runCheck();assertEquals(ConnectionStatus.State.NON_CAMPUS,ConnectionStatus.get().state);
        campus=true;rootBody=MonitoringPolicyTest.LOGIN_FORM;runCheck();assertEquals(ConnectionStatus.State.OFFLINE,ConnectionStatus.get().state);assertEquals(2,calls.stream().filter(u->u.equals(PortalClient.HOST)).count());
    }
    @Test public void postLoginVerificationIsNotDelayedByEarlierFailedProbes()throws Exception{
        String id=store.saveAccount(null,"test","test-student","test-pass");store.selectAccount(id);runCheck();assertEquals(0,store.retry().failures.size());
        internet=true;ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(2));runCheck(RunGate.READ_ONLY);assertEquals(ConnectionStatus.State.ONLINE,ConnectionStatus.get().state);
        assertFalse(ConnectionStatus.get().detail.contains("限流"));
    }
    @Test public void explicitConnectionCheckBypassesHealthyCacheWhenNetworkStopsWorking()throws Exception{
        serverStatus=1;internet=true;runCheck();assertEquals(ConnectionStatus.State.ONLINE,ConnectionStatus.get().state);
        internet=false;ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(300));runCheck();assertEquals(ConnectionStatus.State.SIGNED_IN,ConnectionStatus.get().state);
    }
    private void waitIdle()throws Exception{long deadline=System.currentTimeMillis()+5000;while(CheckCoordinator.running()&&System.currentTimeMillis()<deadline)Thread.sleep(10);assertFalse(CheckCoordinator.running());}
    private void savedAccount()throws Exception{String id=store.saveAccount(null,"test","test-student","test-pass");store.selectAccount(id);}
    private long logins(){return calls.stream().filter(u->u.contains("/login?")).count();}
    private long xhaCalls(){return calls.stream().filter(u->u.startsWith("https://xha.ouc.edu.cn")).count();}
    @Test public void manualReconnectResetsPauseAndAuthenticatesImmediately()throws Exception{
        savedAccount();long now=System.currentTimeMillis();for(int i=0;i<3;i++)store.recordAuthFailure(store.config(),now+i);assertTrue(store.retry().blocked);
        CheckCoordinator.reconnect(context,null);waitIdle();assertEquals(1,logins());assertFalse(store.retry().blocked);assertEquals("actual-user",SessionInfoRepository.get().info.account);
    }
    @Test public void publicInternetSuccessSkipsAllXhaRequestsEvenWhenPortalWouldBeUnauthenticated()throws Exception{
        savedAccount();internet=true;runCheck();assertEquals(0,xhaCalls());assertEquals(1,calls.size());assertEquals(0,logins());assertEquals(ConnectionStatus.State.ONLINE,ConnectionStatus.get().state);assertNull(SessionInfoRepository.get().info);
    }
    @Test public void signedInWithExternalFailureOrMissingUidNeverSubmitsPassword()throws Exception{
        savedAccount();serverStatus=1;serverAccount="";runCheck();assertEquals(0,logins());assertEquals(ConnectionStatus.State.SIGNED_IN,ConnectionStatus.get().state);assertTrue(store.retry().failures.isEmpty());
    }
    @Test public void detailFailurePreservesAuthenticationAndNewAccountSummary()throws Exception{
        savedAccount();serverStatus=1;detailsFail=true;runCheck();assertEquals(0,logins());assertEquals("actual-user",SessionInfoRepository.get().info.account);assertFalse(SessionInfoRepository.get().complete);assertEquals(ConnectionStatus.State.SIGNED_IN,ConnectionStatus.get().state);
    }
    @Test public void unknownXhaStatusDoesNotLoginOrCountFailure()throws Exception{
        savedAccount();serverStatus=7;runCheck();assertEquals(0,logins());assertEquals(ConnectionStatus.State.INDETERMINATE,ConnectionStatus.get().state);assertTrue(store.retry().failures.isEmpty());
    }
    @Test public void confirmedFailuresCanRetryWithinOneSecondAndThirdPauses()throws Exception{
        savedAccount();loginResult=0;for(int i=0;i<3;i++)runCheck();assertEquals(3,logins());assertTrue(store.retry().blocked);runCheck();assertEquals(3,logins());
        CheckCoordinator.reconnect(context,null);waitIdle();assertEquals(4,logins());assertEquals(1,store.retry().failures.size());
    }
    @Test public void manualRefreshAlwaysRequestsXhaAndReplacesCachedAccountInsideTenSeconds()throws Exception{
        serverStatus=1;internet=true;CheckCoordinator.reconnect(context,null);waitIdle();long roots=calls.stream().filter(u->u.equals(PortalClient.HOST)).count();
        serverAccount="new-actual-user";CheckCoordinator.reconnect(context,null);waitIdle();assertEquals(roots+1,calls.stream().filter(u->u.equals(PortalClient.HOST)).count());assertEquals(serverAccount,SessionInfoRepository.get().info.account);assertEquals(0,logins());
    }
    @Test public void reconnectWithoutWifiStillResetsFailureProtection()throws Exception{
        savedAccount();for(int i=0;i<3;i++)store.recordAuthFailure(store.config(),System.currentTimeMillis()+i);
        Shadows.shadowOf(context.getSystemService(ConnectivityManager.class)).clearAllNetworks();CheckCoordinator.reconnect(context,null);waitIdle();assertFalse(store.retry().blocked);assertTrue(calls.isEmpty());
    }
    @Test public void simultaneousReconnectClicksMergeAndResetOnlyOnce()throws Exception{
        rootEntered=new java.util.concurrent.CountDownLatch(1);rootRelease=new java.util.concurrent.CountDownLatch(1);
        CheckCoordinator.reconnect(context,null);assertTrue(rootEntered.await(2,java.util.concurrent.TimeUnit.SECONDS));CheckCoordinator.reconnect(context,null);
        rootRelease.countDown();waitIdle();assertEquals(1,calls.stream().filter(u->u.equals(PortalClient.HOST)).count());assertEquals(1,store.logs().split("用户重新连接：失败保护已重置",-1).length-1);
    }
    @Test public void reconnectCancelsOldRoundAndPreventsItsResultFromOverwriting()throws Exception{
        savedAccount();rootEntered=new java.util.concurrent.CountDownLatch(1);rootRelease=new java.util.concurrent.CountDownLatch(1);
        CheckCoordinator.request(context,RunGate.MANUAL,null);assertTrue(rootEntered.await(2,java.util.concurrent.TimeUnit.SECONDS));
        CheckCoordinator.reconnect(context,null);rootRelease.countDown();waitIdle();assertEquals(1,logins());assertEquals("actual-user",SessionInfoRepository.get().info.account);assertEquals(0,store.retry().failures.size());
    }
    @Test public void networkPermissionDenialStopsRequestsButMissingLocationDoesNot()throws Exception{
        savedAccount();Shadows.shadowOf((Application)context).denyPermissions(android.Manifest.permission.INTERNET);runCheck();assertTrue(calls.isEmpty());assertEquals("没有网络或位置权限",ConnectionStatus.get().detail);
        Shadows.shadowOf((Application)context).grantPermissions(android.Manifest.permission.INTERNET);Shadows.shadowOf((Application)context).denyPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION);runCheck();assertEquals(1,logins());
    }
    @Test public void passwordErrorIsImmediatelyVisibleAndSubsequentTicksSendNoXhaRequests()throws Exception{
        savedAccount();store.setEnabled(true);loginResult=0;loginMessage="密码错误";runCheck(RunGate.AUTOMATIC);
        assertEquals(AuthError.PASSWORD,store.authError(store.config()));assertEquals(ConnectionStatus.State.CREDENTIAL_ERROR,ConnectionStatus.get().state);assertTrue(ConnectionStatus.get().detail.contains("密码错误"));
        long before=xhaCalls();assertEquals(6,before);for(int i=0;i<20;i++){ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1));runCheck(RunGate.AUTOMATIC);}
        assertEquals(before,xhaCalls());assertEquals(1,logins());assertEquals(1,store.retry().failures.size());assertEquals(AuthError.PASSWORD,new AppStore(context).authError(store.config()));
    }
    @Test public void manualReconnectCanRetryAfterAmbiguousCredentialErrorAndClearWarningOnSuccess()throws Exception{
        savedAccount();loginResult=0;loginMessage="账号或密码错误";runCheck();assertEquals(AuthError.ACCOUNT_OR_PASSWORD,store.authError(store.config()));
        loginResult=1;internet=true;ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(300));CheckCoordinator.reconnect(context,null);waitIdle();assertEquals(2,logins());assertEquals(AuthError.NONE,store.authError(store.config()));assertEquals(ConnectionStatus.State.ONLINE,ConnectionStatus.get().state);
    }
    @Test public void changingRealPasswordReleasesOnlyTheCredentialWarningAndAllowsNewAttempt()throws Exception{
        savedAccount();loginResult=0;loginMessage="incorrect password";runCheck();SettingsData.Account a=store.config().active();
        store.saveAccount(a.id,a.label,a.username,"corrected-secret");assertEquals(AuthError.NONE,store.authError(store.config()));loginResult=1;runCheck();assertEquals(2,logins());assertEquals(AuthError.NONE,store.authError(store.config()));
    }
    @Test public void captchaRejectionIsNotReportedAsPasswordError()throws Exception{
        savedAccount();loginResult=0;loginMessage="密码正确，验证码错误";runCheck();assertEquals(AuthError.NONE,store.authError(store.config()));assertNotEquals(ConnectionStatus.State.CREDENTIAL_ERROR,ConnectionStatus.get().state);
    }
    @Test public void tenMinutesOfHealthyAutomaticTicksNeverRefreshXhaOrExpireDisplayedIdentity()throws Exception{
        savedAccount();store.setEnabled(true);serverStatus=1;internet=true;CheckCoordinator.reconnect(context,null);waitIdle();
        SessionInfoClient.Info info=SessionInfoRepository.get().info;assertNotNull(info);long requests=xhaCalls(),meter=RequestControl.GLOBAL.total();calls.clear();
        for(int i=0;i<600;i++){ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(700));runCheck(RunGate.AUTOMATIC);}
        assertEquals(0,xhaCalls());assertEquals(meter,RequestControl.GLOBAL.total());assertTrue(requests>0);assertSame(info,SessionInfoRepository.get().info);
        assertEquals(600,calls.size());for(int i=0;i<calls.size();i++)assertEquals(ProbeScheduler.URLS[(i+1)%3],calls.get(i));
        assertEquals(ConnectionStatus.State.ONLINE,ConnectionStatus.get().state);assertEquals("外网验证通过",ConnectionStatus.get().detail);
        assertFalse(store.logs().contains("XHA在线，账号信息已确认，外网验证通过"));
        assertEquals(1,store.logs().split("外网验证通过",-1).length-1);assertEquals(0,RequestControl.GLOBAL.minute());
    }
    @Test public void healthyNonCampusWifiNeedsNoXhaClassification()throws Exception{
        campus=false;internet=true;runCheck(RunGate.READ_ONLY);assertEquals(0,xhaCalls());assertEquals(ConnectionStatus.State.ONLINE,ConnectionStatus.get().state);
    }
    @Test public void nextAutomaticTickDetectsInternetLossAndAuthenticatesWithoutHealthyCacheDelay()throws Exception{
        savedAccount();store.setEnabled(true);internet=true;runCheck(RunGate.AUTOMATIC);assertEquals(0,xhaCalls());
        internet=false;runCheck(RunGate.AUTOMATIC);assertTrue(xhaCalls()>0);assertEquals(1,logins());assertEquals("actual-user",SessionInfoRepository.get().info.account);
    }
    @Test public void manualReconnectStillRefreshesXhaAndAuthenticatesDespiteWorkingInternet()throws Exception{
        savedAccount();internet=true;runCheck();assertEquals(0,xhaCalls());
        CheckCoordinator.reconnect(context,null);waitIdle();assertTrue(xhaCalls()>0);assertEquals(1,logins());assertEquals("actual-user",SessionInfoRepository.get().info.account);
    }
    @Test public void externalRecoveryAfterPasswordErrorDoesNotResubmitCredentials()throws Exception{
        savedAccount();store.setEnabled(true);loginResult=0;loginMessage="密码错误";runCheck(RunGate.AUTOMATIC);assertEquals(AuthError.PASSWORD,store.authError(store.config()));long before=xhaCalls();
        internet=true;runCheck(RunGate.AUTOMATIC);assertEquals(before,xhaCalls());assertEquals(1,logins());assertEquals(ConnectionStatus.State.ONLINE,ConnectionStatus.get().state);
    }
    @Test public void singleFailedProviderWithoutCaptiveEvidenceDoesNotImmediatelyQueryXha()throws Exception{
        WifiAccess.Link link=WifiAccess.actual(context,true);NetworkCapabilities caps=wifiCapabilities();Shadows.shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);Shadows.shadowOf(context.getSystemService(ConnectivityManager.class)).setNetworkCapabilities(link.network,caps);
        runCheck();assertEquals(0,xhaCalls());assertEquals(ConnectionStatus.State.INDETERMINATE,ConnectionStatus.get().state);
        internet=true;runCheck();assertEquals(0,xhaCalls());assertEquals(ConnectionStatus.State.ONLINE,ConnectionStatus.get().state);
    }
    @Test public void changingAccountOrWifiWhileInternetWorksDoesNotRefreshOrReuseAnotherIdentity()throws Exception{
        savedAccount();serverStatus=1;internet=true;CheckCoordinator.reconnect(context,null);waitIdle();assertNotNull(SessionInfoRepository.get().info);long before=xhaCalls();
        String id=store.saveAccount(null,"other","other-student","other-secret");store.selectAccount(id);runCheck();assertEquals(before,xhaCalls());assertNull(SessionInfoRepository.get().info);
        ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);Shadows.shadowOf(cm).clearAllNetworks();Network n=ShadowNetwork.newInstance(++networkId);
        NetworkInfo info=org.robolectric.shadows.ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,ConnectivityManager.TYPE_WIFI,0,true,true);Shadows.shadowOf(cm).addNetwork(n,info);Shadows.shadowOf(cm).setNetworkCapabilities(n,wifiCapabilities());
        runCheck();assertEquals(before,xhaCalls());assertEquals(0,logins());assertEquals(WifiAccess.key(n),ConnectionStatus.get().key);assertNull(SessionInfoRepository.get().info);
    }
}
