package cn.jieshen.oucautologin;

import android.app.Application;

public final class CampusApplication extends Application {
    @Override public void onCreate(){super.onCreate();RecoveryRepository.get(this);RequestControl.GLOBAL.total();}
}
