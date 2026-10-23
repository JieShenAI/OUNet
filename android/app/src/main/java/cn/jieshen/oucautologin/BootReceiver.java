package cn.jieshen.oucautologin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))) return;
        AppStore store = new AppStore(context);
        if (!store.enabled() || !store.configured()) return;
        try { MonitorService.start(context); }
        catch (RuntimeException error) { store.status("系统未允许自动恢复监测，请解锁后打开应用，并检查该品牌的自启动与后台设置"); }
    }
}
