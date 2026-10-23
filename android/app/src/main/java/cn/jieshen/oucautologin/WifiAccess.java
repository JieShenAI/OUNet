package cn.jieshen.oucautologin;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class WifiAccess {
    enum NameState { READY, MISSING_PERMISSION, LOCATION_DISABLED, BACKGROUND, REDACTED, SYSTEM_UNAVAILABLE }
    private static final ConcurrentHashMap<Network, NetworkCapabilities> observed = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Network,Long> epochs=new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong epoch=new java.util.concurrent.atomic.AtomicLong();
    static String key(Network network){return network.getNetworkHandle()+":"+epochs.computeIfAbsent(network,n->epoch.incrementAndGet());}
    public static final class Link {
        public final Network network;
        public final String ssid;
        public final boolean validated, captive;
        Link(Network network, String ssid, NetworkCapabilities caps) {
            this(network,ssid,caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL));
        }
        Link(Network network,String ssid,boolean validated,boolean captive) {
            this.network=network;this.ssid=ssid;this.validated=validated;this.captive=captive;
        }
    }
    public static final class Snapshot {
        public final List<Link> links;
        public final String device, reason;
        public final boolean wifiEnabled;
        final NameState nameState;final boolean networkPermissionMissing;
        Snapshot(List<Link> links,String device,String reason,boolean wifiEnabled) {
            this(links,device,reason,wifiEnabled,NameState.READY,false);
        }
        Snapshot(List<Link> links,String device,String reason,boolean wifiEnabled,NameState nameState,boolean networkPermissionMissing) {
            this.links=links; this.device=device; this.reason=reason; this.wifiEnabled=wifiEnabled;
            this.nameState=nameState;this.networkPermissionMissing=networkPermissionMissing;
        }
        public Link target(String ssid) {
            if(ssid.isEmpty()) return null;
            Link match=null;
            for(Link link:links) if(ssid.equals(link.ssid)) { if(match!=null)return null; match=link; }
            return match;
        }
        public String names() {
            if(links.isEmpty())return wifiEnabled?"未连接Wi-Fi":"Wi-Fi已关闭";
            List<String> names=new ArrayList<>();
            for(Link link:links)names.add(link.ssid.isEmpty()?"名称不可见":link.ssid);
            return String.join(" / ",names);
        }
    }
    static boolean networkPermissions(Context context){
        for(String permission:new String[]{Manifest.permission.INTERNET,Manifest.permission.ACCESS_NETWORK_STATE,Manifest.permission.ACCESS_WIFI_STATE})
            if(context.checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED)return false;
        return true;
    }
    static String title(Snapshot snapshot,Link actual){
        if(snapshot.networkPermissionMissing||snapshot.nameState==NameState.MISSING_PERMISSION)return "没有网络或位置权限";
        if(!snapshot.wifiEnabled&&snapshot.links.isEmpty())return "Wi-Fi已关闭";
        if(snapshot.links.isEmpty())return "Wi-Fi未连接";
        if(actual==null)return "正在确认Wi-Fi";
        if(!actual.ssid.isEmpty())return actual.ssid;
        if(snapshot.nameState==NameState.LOCATION_DISABLED)return "系统定位未开启";
        return "Wi-Fi名称暂不可见";
    }
    private static NameState nameState(Context context,boolean foreground){
        if(!foreground)return NameState.BACKGROUND;
        if(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
            return NameState.MISSING_PERMISSION;
        LocationManager location=context.getSystemService(LocationManager.class);
        try{return location==null||!location.isLocationEnabled()?NameState.LOCATION_DISABLED:NameState.READY;}
        catch(SecurityException e){return NameState.MISSING_PERMISSION;}
    }
    public static String nameProblem(Context context,boolean foreground) {
        switch(nameState(context,foreground)){
            case BACKGROUND:return "后台不读取Wi-Fi名称";
            case MISSING_PERMISSION:return "请允许精确位置权限，近似位置不足以读取Wi-Fi名称";
            case LOCATION_DISABLED:return "请开启系统定位，Android需要它提供Wi-Fi名称";
            default:return "";
        }
    }
    private static String name(NetworkCapabilities caps) {
        if(Build.VERSION.SDK_INT>=29&&caps!=null&&caps.getTransportInfo() instanceof WifiInfo)
            return CheckSchedule.cleanSsid(((WifiInfo)caps.getTransportInfo()).getSSID());
        return "";
    }
    @SuppressLint("MissingPermission") // Exact location and location toggle are checked before SSID access.
    @SuppressWarnings("deprecation")
    public static Snapshot snapshot(Context context,boolean foreground) {
        ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
        WifiManager wifi=(WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        String reason=nameProblem(context,foreground),device="无默认网络";
        NameState nameState=nameState(context,foreground);
        boolean wifiEnabled=false,networkPermissionMissing=!networkPermissions(context);
        List<Link> links=new ArrayList<>();
        try{
        wifiEnabled=wifi!=null&&wifi.isWifiEnabled();
        if(manager==null)return new Snapshot(links,device,"无法读取系统网络状态",wifiEnabled,NameState.SYSTEM_UNAVAILABLE,networkPermissionMissing);
        Network active=manager.getActiveNetwork();
        NetworkCapabilities defaults=active==null?null:manager.getNetworkCapabilities(active);
        if(defaults!=null) {
            String route=defaults.hasTransport(NetworkCapabilities.TRANSPORT_VPN)?"VPN":defaults.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?"移动数据":defaults.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"Wi-Fi":"其它网络";
            device=route+(defaults.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)?"可用（系统已验证）":"已连接，系统尚未验证外网");
        }
        for(Network network:manager.getAllNetworks()) {
            NetworkCapabilities caps=manager.getNetworkCapabilities(network);
            if(caps==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))continue;
            String ssid="";
            if(reason.isEmpty()) {
                ssid=name(observed.get(network));
                if(ssid.isEmpty())ssid=name(caps);
            }
            links.add(new Link(network,ssid,caps));
        }
        // Never infer the identity of either of two Wi-Fi networks from the global legacy getter.
        if(reason.isEmpty()&&Build.VERSION.SDK_INT<=30&&links.size()==1&&links.get(0).ssid.isEmpty()&&wifi!=null) {
            try {
                WifiInfo info=wifi.getConnectionInfo(); Link first=links.get(0);
                NetworkCapabilities caps=manager.getNetworkCapabilities(first.network);
                if(info!=null&&caps!=null)links.set(0,new Link(first.network,CheckSchedule.cleanSsid(info.getSSID()),caps));
            } catch(SecurityException e) {reason="系统限制读取Wi-Fi名称，请检查位置权限";nameState=NameState.MISSING_PERMISSION;}
        }
        if(reason.isEmpty())for(Link link:links)if(link.ssid.isEmpty()){reason="Wi-Fi名称暂不可见，请稍候刷新并检查精确位置权限";nameState=NameState.REDACTED;break;}
        }catch(SecurityException e){networkPermissionMissing=true;reason="系统拒绝读取网络状态，请检查应用权限";nameState=NameState.MISSING_PERMISSION;}
        return new Snapshot(links,device,reason,wifiEnabled,nameState,networkPermissionMissing);
    }
    public static Link actual(Context context,boolean foreground){
        Snapshot snapshot=snapshot(context,foreground);ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);
        Network preferred=null;try{preferred=cm==null?null:cm.getActiveNetwork();}catch(SecurityException ignored){}return choose(snapshot.links,preferred);
    }
    static Link choose(List<Link> links,Network preferred){
        if(preferred!=null)for(Link link:links)if(preferred.equals(link.network))return link;
        return links.size()==1?links.get(0):null;
    }
    public static boolean matches(Context context,Network network,String ignored,boolean foreground){Link link=actual(context,foreground);return link!=null&&link.network.equals(network);}
    public static final class Observer implements AutoCloseable {
        private final ConnectivityManager manager;
        private final ConnectivityManager.NetworkCallback callback;
        private boolean registered;
        public Observer(Context context,Runnable changed) {
            manager=context.getSystemService(ConnectivityManager.class);
            callback=Build.VERSION.SDK_INT>=31?new ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                @Override public void onAvailable(Network n){changed.run();}
                @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c){update(n,c,changed);}
                @Override public void onLost(Network n){lost(n,changed);}
            }:new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n){changed.run();}
                @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c){update(n,c,changed);}
                @Override public void onLost(Network n){lost(n,changed);}
            };
            if(manager!=null)try {
                manager.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),callback);
                registered=true;
            }catch(SecurityException e){new AppStore(context).log("Wi-Fi监听未获系统允许，请检查应用权限");}
        }
        private static void update(Network n,NetworkCapabilities c,Runnable changed) {
            NetworkCapabilities old=observed.put(n,c);
            if(old!=null&&!name(old).isEmpty()&&!name(c).isEmpty()&&!name(old).equals(name(c))){epochs.remove(n);CheckCoordinator.cancel();ConnectionStatus.invalidate("连接已变化，正在检测");}
            changed.run();
        }
        private static void lost(Network n,Runnable changed){observed.remove(n);epochs.remove(n);CheckCoordinator.cancel();ConnectionStatus.invalidate("连接已断开，正在检测");changed.run();}
        @Override public void close(){if(registered){registered=false;try{manager.unregisterNetworkCallback(callback);}catch(IllegalArgumentException ignored){}}}
    }
}
