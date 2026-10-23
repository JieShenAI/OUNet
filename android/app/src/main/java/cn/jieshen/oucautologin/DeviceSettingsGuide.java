package cn.jieshen.oucautologin;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.app.NotificationManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import java.util.Locale;

public final class DeviceSettingsGuide {
    public static final String[] BRANDS={"小米 / Redmi","iQOO","vivo","OPPO","其它Android"};
    private DeviceSettingsGuide(){}
    public static int detected(){
        String value=(Build.BRAND+" "+Build.MANUFACTURER).toLowerCase(Locale.ROOT);
        if(value.contains("iqoo"))return 1;
        if(value.contains("xiaomi")||value.contains("redmi"))return 0;
        if(value.contains("vivo"))return 2;
        if(value.contains("oppo"))return 3;
        return 4;
    }
    public static String guide(int brand){
        String common="前台Wi-Fi名称读取需精确位置和系统定位；名称不可见不影响已唯一确认Network的检测。后台监测请允许通知，并按需解除省电限制。\n\n";
        String specific;
        switch(brand){
            case 0:specific="MIUI / HyperOS：查找应用的后台自启动，按需允许；在应用省电策略中选择无限制，可在最近任务中保留应用。";break;
            case 1:specific="iQOO：在应用与权限/权限管理中查找自启动；在电池/后台耗电管理中允许后台运行。OriginOS和海外系统的菜单可能不同，需分别验证。";break;
            case 2:specific="vivo：在应用与权限中查找自启动；在电池/后台耗电管理中允许后台运行。OriginOS与Funtouch OS入口可能不同。";break;
            case 3:specific="ColorOS：在应用管理中查找自启动；在应用耗电管理中允许后台活动，按需在最近任务中保留应用。";break;
            default:specific="请在应用信息、位置权限、电池优化和后台运行设置中检查限制。";
        }
        return common+specific+"\n\n菜单因机型、地区和系统版本而异。应用无法自动确认厂商自启动白名单，打开设置页不表示已开启。锁屏休眠和请求耗时可能延后状态评估。需在实际手机验证。";
    }
    public static String permissions(Context context){
        boolean fine=context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
        LocationManager lm=context.getSystemService(LocationManager.class);PowerManager pm=context.getSystemService(PowerManager.class);
        NotificationManager nm=context.getSystemService(NotificationManager.class);
        String location;try{location=lm!=null&&lm.isLocationEnabled()?"已开启":"未开启";}catch(SecurityException e){location="系统拒绝读取，请检查权限";}
        return "系统：Android "+Build.VERSION.RELEASE+" / API "+Build.VERSION.SDK_INT+"\n品牌："+Build.BRAND+" / "+Build.MANUFACTURER
            +"\n精确位置："+(fine?"已授权":"未授权")+"\n系统定位："+location
            +"\n网络读取权限："+(WifiAccess.networkPermissions(context)?"已允许":"未允许；顶部显示没有网络或位置权限")
            +"\n后台位置：本版不申请；SSID仅作展示"
            +"\n通知："+(nm!=null&&nm.areNotificationsEnabled()?"已允许":"未允许")
            +"\n电池优化："+(pm!=null&&pm.isIgnoringBatteryOptimizations(context.getPackageName())?"已不受通用优化限制":"受系统优化；厂商策略需另行检查")
            +"\n厂商自启动白名单：无法自动确认";
    }
    public static void appSettings(Context context){open(context,new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+context.getPackageName())));}
    public static void open(Context context,Intent intent){
        try{context.startActivity(intent);}catch(RuntimeException e){try{context.startActivity(new Intent(Settings.ACTION_SETTINGS));}catch(RuntimeException ignored){new AppStore(context).status("系统未提供此设置入口，请手动进入系统设置");}}
    }
}
