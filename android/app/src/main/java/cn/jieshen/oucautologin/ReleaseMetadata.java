package cn.jieshen.oucautologin;

import java.io.IOException;
import org.json.JSONObject;

final class ReleaseMetadata {
    static final String REPO="https://github.com/JieShenAI/OUNet";
    static final String APP_ID="cn.jieshen.oucautologin";
    static final String CERT="6a5eedfcb02875007673e86772bc9da399e48e80f24dd88b85976126296fd91f";
    final int code,minSdk;final String name,notes,releaseUrl,apkUrl,sha256;
    private ReleaseMetadata(int code,int minSdk,String name,String notes,String release,String apk,String hash){this.code=code;this.minSdk=minSdk;this.name=name;this.notes=notes;releaseUrl=release;apkUrl=apk;sha256=hash;}
    static ReleaseMetadata parse(JSONObject json,String tag)throws IOException {
        if(!tag.matches("android-v[0-9]+\\.[0-9]+\\.[0-9]+"))throw new IOException("不是Android正式版本");
        String name=json.optString("versionName"),url=json.optString("releaseUrl"),apk=json.optString("apkUrl"),hash=json.optString("sha256");
        int code=json.optInt("versionCode",-1),sdk=json.optInt("minSdk",-1);
        if(!APP_ID.equals(json.optString("applicationId"))||!CERT.equalsIgnoreCase(json.optString("signingCertSha256"))||code<1||sdk<28||!tag.equals("android-v"+name)||!url.equals(REPO+"/releases/tag/"+tag)||!apk.equals(REPO+"/releases/download/"+tag+"/ouc-autologin-"+name+"-release.apk")||!hash.matches("[a-fA-F0-9]{64}"))throw new IOException("Android更新元数据校验失败");
        String notes=SessionInfoClient.clean(json.opt("releaseNotes"),8000);return new ReleaseMetadata(code,sdk,name,notes,url,apk,hash.toLowerCase(java.util.Locale.ROOT));
    }
}
