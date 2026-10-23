package cn.jieshen.oucautologin;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w360dp-h800dp-xxhdpi",application=Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AndroidExperienceTest {
    private Context context;
    @Before public void reset()throws Exception{
        context=RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf((Application)context).grantPermissions(android.Manifest.permission.INTERNET,android.Manifest.permission.ACCESS_NETWORK_STATE,android.Manifest.permission.ACCESS_WIFI_STATE);
        context.getSharedPreferences("ouc_local",Context.MODE_PRIVATE).edit().clear().commit();
        Field f=RecoveryRepository.class.getDeclaredField("instance");f.setAccessible(true);RecoveryRepository previous=(RecoveryRepository)f.get(null);if(previous!=null)previous.close();f.set(null,null);context.deleteDatabase("recovery_v1.db");
        CheckCoordinator.cancel();ConnectionStatus.invalidate("正在检测");
    }
    @Test public void sqliteLedgerDeduplicatesAndSeparatesExternalRecoveries(){
        RecoveryRepository repo=RecoveryRepository.get(context);
        repo.offline("n1","test");repo.attempt("n1");repo.recovered("n1",true,"20260001","切换账号");repo.recovered("n1",true,"20260001","重复回调");
        assertEquals(1,repo.count());assertEquals(1,repo.page(0,30).size());assertEquals("20****01",repo.page(0,1).get(0).account);
        repo.offline("n1","test");repo.recovered("n1",false,"","周期检测");assertEquals(1,repo.count());assertEquals(2,repo.page(0,30).size());
        repo.offline("n1","test");repo.connection("n2");repo.recovered("n2",true,"20260001","切网");assertEquals(2,repo.page(0,30).size());
    }
    @Test public void sqliteTimelineKeepsEveryCurrentLaunchEventAndPages(){
        RecoveryRepository repo=RecoveryRepository.get(context);for(int i=0;i<65;i++){repo.offline("n1","test");repo.recovered("n1",true,"student","周期检测");}
        assertEquals(65,repo.count());assertEquals(30,repo.page(0,30).size());assertEquals(30,repo.page(30,30).size());assertEquals(5,repo.page(60,30).size());
    }
    @Test public void newProcessLaunchDoesNotReuseCountsOrResetAuthenticationProtection()throws Exception{
        RecoveryRepository old=RecoveryRepository.get(context);old.offline("n1","test");old.recovered("n1",true,"student","手动连接");String previousLaunch=old.launchId;
        AppStore store=new AppStore(context);store.recordAuthFailure(store.config(),System.currentTimeMillis());
        Field f=RecoveryRepository.class.getDeclaredField("instance");f.setAccessible(true);old.close();f.set(null,null);
        RecoveryRepository next=RecoveryRepository.get(context);assertNotEquals(previousLaunch,next.launchId);assertEquals(0,next.count());assertTrue(next.page(0,30).isEmpty());assertEquals(1,store.retry().failures.size());
    }
    @Test public void homeAndNavigationHaveNoticeAndNoRemovedBrandOrNetworkRows()throws Exception{
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible()){
            MainActivity activity=controller.get();View root=activity.findViewById(android.R.id.content);
            assertNotNull(find(root,"免费软件，仅用于学习交流"));assertNotNull(find(root,"OUNet"));assertNotNull(find(root,"认证使用账号"));
            String all=contents(root);assertFalse(all.contains("中国海洋大学"));assertFalse(all.contains("本机网络："));assertFalse(all.contains("目标Wi-Fi："));assertFalse(all.contains("当前Wi-Fi："));
            find(root,"设置").performClick();assertNotNull(find(root,"保存间隔"));assertNotNull(find(root,"免费软件，仅用于学习交流"));
            find(root,"恢复记录").performClick();assertNotNull(find(root,"本次启动已协助恢复 0 次"));find(root,"连接概览").performClick();
            snapshot(root,"overview-api35.png",1080,2400);
        }
    }
    private NetworkFixture network()throws Exception{return new NetworkFixture(context);}
    @Test public void passwordWarningRemainsVisibleAndProvidesAnEditAction()throws Exception{
        NetworkFixture n=network();SettingsData.Account account=new SettingsData.Account("synthetic-id","常用账号","synthetic-student","test-cipher","test-iv",1);
        SettingsData config=new SettingsData(java.util.Collections.singletonList(account),account.id,"",1,false,5);
        context.getSharedPreferences("ouc_local",0).edit().putString("settings_v2",config.json().toString()).putInt("schema_version",3).commit();
        AppStore store=new AppStore(context);store.rememberAuthError(store.config(),AuthError.PASSWORD);
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).create().start()){
            MainActivity a=c.get();ConnectionStatus.set(ConnectionStatus.State.CREDENTIAL_ERROR,WifiAccess.key(n.network),5,AuthError.PASSWORD.guidance());refresh(a);View root=a.findViewById(android.R.id.content);
            assertNotNull(find(root,"密码错误"));assertNotNull(find(root,"修改密码"));Field panel=MainActivity.class.getDeclaredField("credentialPanel");panel.setAccessible(true);assertEquals(View.VISIBLE,((View)panel.get(a)).getVisibility());snapshot(root,"password-error-api35.png",1080,2400);
            navigate(a,2);navigate(a,0);assertNotNull(find(root,"密码错误"));find(root,"修改密码").performClick();android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertTrue(contents(dialog.getWindow().getDecorView()).contains("校园网密码"));dialog.dismiss();
        }
    }
    @Test public void deniedNetworkPermissionDoesNotKeepStaleOnlineStatus()throws Exception{
        NetworkFixture n=network();try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).create().start()){
            MainActivity a=c.get();long generation=new AppStore(context).config().generation;ConnectionStatus.set(ConnectionStatus.State.ONLINE,WifiAccess.key(n.network),generation,"校园网 · 已联网");
            org.robolectric.Shadows.shadowOf((Application)context).denyPermissions(android.Manifest.permission.INTERNET);refresh(a);View root=a.findViewById(android.R.id.content);assertNotNull(find(root,"权限不足"));assertNull(find(root,"已联网"));
        }
    }
    @Test public void internetOnlySuccessExplainsMissingIdentityAndHidesOldCredentialWarning()throws Exception{
        NetworkFixture n=network();SettingsData.Account account=new SettingsData.Account("synthetic-id","常用账号","synthetic-student","test-cipher","test-iv",1);
        SettingsData config=new SettingsData(java.util.Collections.singletonList(account),account.id,"",1,false,5);
        context.getSharedPreferences("ouc_local",0).edit().putString("settings_v2",config.json().toString()).putInt("schema_version",3).commit();
        AppStore store=new AppStore(context);store.rememberAuthError(store.config(),AuthError.PASSWORD);
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).create().start()){
            MainActivity a=c.get();ConnectionStatus.set(ConnectionStatus.State.ONLINE,WifiAccess.key(n.network),5,"外网验证通过");refresh(a);View root=a.findViewById(android.R.id.content);
            assertNotNull(find(root,"已联网"));assertNotNull(find(root,"尚未读取"));assertNotNull(find(root,"外网可用，未查询XHA；可点击刷新读取账号信息"));assertFalse(contents(root).contains("校园网 · 已联网"));
            Field panel=MainActivity.class.getDeclaredField("credentialPanel");panel.setAccessible(true);assertEquals(View.GONE,((View)panel.get(a)).getVisibility());assertEquals(AuthError.PASSWORD,store.authError(store.config()));
            snapshot(root,"internet-only-api35.png",1080,2400);
        }
    }
    @Test public void deniedLocationShowsExplicitHeaderAndDiagnosisUpdatesNewestFirst()throws Exception{
        network();org.robolectric.Shadows.shadowOf((Application)context).denyPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION);
        AppStore store=new AppStore(context);store.log("older-ui-entry");store.log("newer-ui-entry");
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).create().start()){
            MainActivity a=c.get();assertNotNull(find(a.findViewById(android.R.id.content),"没有网络或位置权限"));navigate(a,2);find(a.findViewById(android.R.id.content),"诊断记录").performClick();
            android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();String content=contents(dialog.getWindow().getDecorView());assertTrue(content.indexOf("newer-ui-entry")<content.indexOf("older-ui-entry"));
            Field visible=MainActivity.class.getDeclaredField("visible");visible.setAccessible(true);visible.set(a,true);store.log("latest-live-ui-entry");org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1001));
            content=contents(dialog.getWindow().getDecorView());assertTrue(content.contains("latest-live-ui-entry"));assertTrue(content.indexOf("latest-live-ui-entry")<content.indexOf("newer-ui-entry"));snapshot(dialog.getWindow().getDecorView(),"diagnostics-api35.png",1080,1600);
            dialog.dismiss();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Field field=MainActivity.class.getDeclaredField("logsDialog");field.setAccessible(true);assertNull(field.get(a));visible.set(a,false);
        }
    }
    private static class NetworkFixture {
        final android.net.Network network;
        NetworkFixture(Context c){android.net.ConnectivityManager manager=c.getSystemService(android.net.ConnectivityManager.class);org.robolectric.Shadows.shadowOf(manager).clearAllNetworks();network=org.robolectric.shadows.ShadowNetwork.newInstance(4001);
            android.net.NetworkInfo info=org.robolectric.shadows.ShadowNetworkInfo.newInstance(android.net.NetworkInfo.DetailedState.CONNECTED,android.net.ConnectivityManager.TYPE_WIFI,0,true,true);org.robolectric.Shadows.shadowOf(manager).addNetwork(network,info);
            android.net.NetworkCapabilities caps=new android.net.NetworkCapabilities();org.robolectric.Shadows.shadowOf(caps).addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI);org.robolectric.Shadows.shadowOf(manager).setNetworkCapabilities(network,caps);
        }
    }
    private void refresh(MainActivity a)throws Exception{java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod("refresh");m.setAccessible(true);m.invoke(a);}
    private void navigate(MainActivity a,int page)throws Exception{java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod("navigate",int.class);m.setAccessible(true);m.invoke(a,page);}
    @Test public void nonCampusIconAndReadOnlyHeaderRender()throws Exception{
        NetworkFixture n=network();try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).create().start()){
            MainActivity a=c.get();ConnectionStatus.set(ConnectionStatus.State.NON_CAMPUS,WifiAccess.key(n.network),new AppStore(context).config().generation,"XHA访问超时");refresh(a);View root=a.findViewById(android.R.id.content);
            assertNotNull(find(root,"非校园网"));assertFalse(contents(root).contains("选择Wi-Fi"));assertFalse(contents(root).contains("重新扫描"));Field f=MainActivity.class.getDeclaredField("wifiName");f.setAccessible(true);assertFalse(((View)f.get(a)).isClickable());
            Field icon=MainActivity.class.getDeclaredField("prohibited");icon.setAccessible(true);assertEquals(View.VISIBLE,((View)icon.get(a)).getVisibility());snapshot(root,"non-campus-api35.png",1080,2400);
        }
    }
    @Test public void onlineFieldsDevicesAndStatisticsRenderWithSyntheticData()throws Exception{
        NetworkFixture n=network();try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).create().start()){
            MainActivity a=c.get();String key=WifiAccess.key(n.network);long generation=new AppStore(context).config().generation;SessionInfoClient.Info info=new SessionInfoClient.Info("20260001","10-23 10:18:42","192.0.2.23","2001:db8:1234:5678:abcd:1234:5678:1234","02:00:00:00:00:23","移动端（1）");
            ConnectionStatus.set(ConnectionStatus.State.ONLINE,key,generation,"校园网 · 已联网");Field f=SessionInfoRepository.class.getDeclaredField("value");f.setAccessible(true);f.set(null,new SessionInfoRepository.Value(key,generation,false,info,""));refresh(a);View root=a.findViewById(android.R.id.content);assertNotNull(find(root,info.ipv6));assertNotNull(find(root,"查看其他在线设备 →"));snapshot(root,"online-api35.png",1080,2400);
            java.util.List<SessionInfoClient.Device> devices=new java.util.ArrayList<>();devices.add(new SessionInfoClient.Device(info,true,"one"));devices.add(new SessionInfoClient.Device(new SessionInfoClient.Info("20260001","10-23 09:00:00","192.0.2.58",SessionInfoClient.MISSING,"02:00:00:00:00:58","PC端（0）"),false,"two"));
            Field d=DeviceRepository.class.getDeclaredField("value");d.setAccessible(true);d.set(null,new DeviceRepository.Value(key,generation,"20260001","已读取全部可见设备",false,true,devices,android.os.SystemClock.elapsedRealtime()));navigate(a,3);assertNotNull(find(root,"PC端（0）"));assertNotNull(find(root,"已加载2台 · 全部可见设备"));snapshot(root,"devices-api35.png",1080,2400);
            navigate(a,1);assertNotNull(find(root,"校园网调用（XHA）"));snapshot(root,"recovery-api35.png",1080,2400);navigate(a,2);assertNotNull(find(root,"APP介绍"));assertNotNull(find(root,"数据使用说明"));assertNotNull(find(root,"版权与第三方声明"));snapshot(root,"settings-api35.png",1080,2400);
        }
    }
    @Test @Config(sdk=28,qualifiers="w360dp-h800dp-xxhdpi",application=Application.class)
    public void androidNineCanOpenSettingsAndRetainsFreeNotice()throws Exception{
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).create().start()){
            MainActivity a=c.get();navigate(a,2);View root=a.findViewById(android.R.id.content);assertNotNull(find(root,"保存间隔"));assertNotNull(find(root,"免费软件，仅用于学习交流"));
        }
    }
    @Test @Config(sdk=35,qualifiers="w320dp-h700dp-xxhdpi",application=Application.class)
    public void narrowLargeFontKeepsFullIpv6AndFooter()throws Exception{
        android.content.res.Configuration configuration=new android.content.res.Configuration(context.getResources().getConfiguration());configuration.fontScale=1.5f;android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();metrics.setTo(context.getResources().getDisplayMetrics());metrics.scaledDensity=metrics.density*1.5f;context.getResources().updateConfiguration(configuration,metrics);
        NetworkFixture n=network();try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).create().start()){
            MainActivity a=c.get();String key=WifiAccess.key(n.network);long gen=new AppStore(context).config().generation;String ipv6="2001:db8:1234:5678:abcd:1234:5678:1234";SessionInfoClient.Info info=new SessionInfoClient.Info("synthetic-account","10-23 08:00:00","192.0.2.23",ipv6,"02:00:00:00:00:23","移动端（1）");
            ConnectionStatus.set(ConnectionStatus.State.ONLINE,key,gen,"校园网 · 已联网");Field f=SessionInfoRepository.class.getDeclaredField("value");f.setAccessible(true);f.set(null,new SessionInfoRepository.Value(key,gen,false,info,""));refresh(a);View root=a.findViewById(android.R.id.content);snapshot(root,"online-narrow-large-api35.png",960,2100);
            TextView address=find(root,ipv6);assertNotNull(address);assertTrue(address.getLayout().getLineCount()>1);for(int line=0;line<address.getLayout().getLineCount();line++)assertEquals(0,address.getLayout().getEllipsisCount(line));TextView footer=find(root,"免费软件，仅用于学习交流");assertTrue(footer.getBottom()<=((View)footer.getParent()).getHeight());
        }
    }
    private static TextView find(View view,String text){if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return (TextView)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){TextView found=find(group.getChildAt(i),text);if(found!=null)return found;}}return null;}
    private static String contents(View view){StringBuilder s=new StringBuilder();if(view instanceof TextView)s.append(((TextView)view).getText()).append('\n');if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++)s.append(contents(g.getChildAt(i)));}return s.toString();}
    private static void snapshot(View root,String name,int width,int height)throws Exception{
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));File folder=new File(System.getProperty("qa.output"));if(!folder.exists()&&!folder.mkdirs())throw new java.io.IOException("QA output unavailable");try(FileOutputStream out=new FileOutputStream(new File(folder,name))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}bitmap.recycle();
    }
}
