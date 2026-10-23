package cn.jieshen.oucautologin;

import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** User-requested public Release lookup. No token, installation permission or campus data. */
final class UpdateRepository {
    static final class Value {final boolean loading;final String message;final ReleaseMetadata release;Value(boolean loading,String message,ReleaseMetadata release){this.loading=loading;this.message=message;this.release=release;}}
    private static volatile Value value=new Value(false,"点击检查Android正式版本",null);
    private static long last=-1;
    private static final java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor();
    static Value get(){return value;}
    static synchronized void request(){
        long now=SystemClock.elapsedRealtime();if(value.loading||(last>=0&&now-last<60000))return;last=now;value=new Value(true,"正在检查GitHub Android发布…",value.release);
        worker.execute(()->{try{ReleaseMetadata release=fetch();value=new Value(false,release==null?"尚未找到带更新元数据的Android正式发布":release.code>BuildConfig.VERSION_CODE?"发现新版本 "+release.name:"当前已是最新Android正式版本",release);}
            catch(Exception e){value=new Value(false,"暂时无法检查更新，可稍后重试或打开发布页",null);}});
    }
    private static ReleaseMetadata fetch()throws Exception{
        long deadline=SystemClock.elapsedRealtime()+12000;int examined=0;ReleaseMetadata best=null;
        for(int page=1;page<=3;page++){
            JSONArray releases=new JSONArray(get("https://api.github.com/repos/JieShenAI/OUNet/releases?per_page=20&page="+page,deadline));
            for(int i=0;i<releases.length();i++){
                JSONObject release=releases.getJSONObject(i);String tag=release.optString("tag_name");if(release.optBoolean("draft")||release.optBoolean("prerelease")||!tag.matches("android-v[0-9]+\\.[0-9]+\\.[0-9]+"))continue;
                if(++examined>5){if(best!=null&&best.code>BuildConfig.VERSION_CODE)return best;throw new IOException("Android发布元数据未完整读取");}JSONArray assets=release.optJSONArray("assets");if(assets==null)continue;
                for(int j=0;j<assets.length();j++){JSONObject asset=assets.getJSONObject(j);if(!"android-update.json".equals(asset.optString("name")))continue;
                    String expected=ReleaseMetadata.REPO+"/releases/download/"+tag+"/android-update.json";if(!expected.equals(asset.optString("browser_download_url")))throw new IOException("更新来源不符");
                    ReleaseMetadata candidate=ReleaseMetadata.parse(new JSONObject(get(expected,deadline)),tag);if(best==null||candidate.code>best.code)best=candidate;
                }
            }
            if(releases.length()<20)return best;
        }
        if(best!=null&&best.code>BuildConfig.VERSION_CODE)return best;throw new IOException("发布列表尚未读完，请打开Android发布页确认");
    }
    static boolean allowed(URL url){
        if(!"https".equals(url.getProtocol())||url.getUserInfo()!=null||url.getRef()!=null||(url.getPort()!=-1&&url.getPort()!=443))return false;
        if(url.getHost().equals("api.github.com"))return url.getPath().equals("/repos/JieShenAI/OUNet/releases");
        if(url.getHost().equals("github.com"))return url.getPath().matches("/JieShenAI/OUNet/releases/download/android-v[0-9]+\\.[0-9]+\\.[0-9]+/android-update\\.json");
        return url.getHost().equals("release-assets.githubusercontent.com");
    }
    private static String get(String value,long deadline)throws IOException{
        for(int hop=0;hop<4;hop++){
            URL url=new URL(value);if(!allowed(url))throw new IOException("更新地址不在允许范围");long left=deadline-SystemClock.elapsedRealtime();if(left<=0)throw new IOException("更新检查超时");
            HttpURLConnection c=(HttpURLConnection)url.openConnection(java.net.Proxy.NO_PROXY);
            try{
                c.setConnectTimeout((int)Math.min(8000,left));c.setReadTimeout((int)Math.min(8000,left));c.setInstanceFollowRedirects(false);c.setRequestProperty("User-Agent","CampusNetwork-Android/1.3");c.setRequestProperty("Accept","application/vnd.github+json");
                int status=c.getResponseCode();if(status==301||status==302||status==303||status==307||status==308){String location=c.getHeaderField("Location");if(location==null)throw new IOException();value=new URL(url,location).toString();continue;}
                if(status!=200)throw new IOException("GitHub返回HTTP "+status);ByteArrayOutputStream out=new ByteArrayOutputStream();
                try(InputStream stream=c.getInputStream()){byte[] bytes=new byte[4096];int n;while(true){left=deadline-SystemClock.elapsedRealtime();if(left<=0)throw new IOException("更新检查超时");c.setReadTimeout((int)Math.min(8000,left));n=stream.read(bytes);if(n<0)break;if(out.size()+n>256000)throw new IOException("更新响应过大");out.write(bytes,0,n);}}
                return new String(out.toByteArray(),StandardCharsets.UTF_8);
            }finally{c.disconnect();}
        }
        throw new IOException("更新跳转过多");
    }
}
