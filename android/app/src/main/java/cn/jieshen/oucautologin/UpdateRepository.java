package cn.jieshen.oucautologin;

import android.os.Build;
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

/** User-requested public release lookup. No campus data, tokens or installer permissions. */
final class UpdateRepository {
    interface Transport { String get(String url,long deadline)throws Exception; }
    interface Clock { long now(); }
    static final class Value {
        final boolean loading; final String state,message; final ReleaseMetadata release; final long checkedAt;
        Value(boolean loading,String state,String message,ReleaseMetadata release,long checkedAt){
            this.loading=loading;this.state=state;this.message=message;this.release=release;this.checkedAt=checkedAt;
        }
    }
    private static volatile Value value=new Value(false,"idle","点击检查 Android 最新正式版本",null,0);
    private static long last=-1;
    private static final java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor();
    static Value get(){return value;}
    static synchronized void request(){
        long now=SystemClock.elapsedRealtime();
        if(value.loading)return;
        if(last>=0&&now-last<60000){
            value=new Value(false,value.state,"请在 "+Math.max(1,(60000-(now-last)+999)/1000)+" 秒后重新检查。\n"+value.message.replaceFirst("^请在 [0-9]+ 秒后重新检查。\\n",""),value.release,value.checkedAt);
            return;
        }
        last=now;value=new Value(true,"loading","正在检查 GitHub Android 发布…",null,0);
        worker.execute(()->{
            try{Value result=lookup(UpdateRepository::download,SystemClock::elapsedRealtime,BuildConfig.VERSION_CODE,Build.VERSION.SDK_INT);
                value=new Value(false,result.state,result.message,result.release,System.currentTimeMillis());}
            catch(Exception e){String message=e instanceof IOException?e.getMessage():"更新响应无效，请打开发布页确认。";
                value=new Value(false,"error",message==null?"暂时无法检查更新，可稍后重试或打开发布页。":message,null,System.currentTimeMillis());}
        });
    }
    static Value lookup(Transport transport,Clock clock,int localCode,int sdk)throws Exception{
        long deadline=clock.now()+20000;ReleaseMetadata best=null;boolean complete=false;int candidates=0;
        for(int page=1;page<=10;page++){
            if(clock.now()>=deadline)throw new IOException("更新检查超时，尚无法确认最新版本。");
            JSONArray releases=(JSONArray)ReleaseJson.parse(transport.get("https://api.github.com/repos/JieShenAI/OUNet/releases?per_page=30&page="+page,deadline));
            for(int i=0;i<releases.length();i++){
                JSONObject release=releases.getJSONObject(i);String tag=release.optString("tag_name");
                if(release.optBoolean("draft")||release.optBoolean("prerelease")||!tag.matches("android-v[0-9]+\\.[0-9]+\\.[0-9]+"))continue;
                if(++candidates>30)throw new IOException("发布候选尚未读完，无法确认最新版本，请打开发布页。");
                JSONArray assets=release.optJSONArray("assets");if(assets==null)throw new IOException("Android 发布不完整：缺少附件。");
                if(assets.length()>=30){
                    long id=release.optLong("id",-1);if(id<=0)throw new IOException("发布附件编号无效。");
                    assets=new JSONArray();boolean all=false;
                    for(int ap=1;ap<=10;ap++){
                        JSONArray batch=(JSONArray)ReleaseJson.parse(transport.get("https://api.github.com/repos/JieShenAI/OUNet/releases/"+id+"/assets?per_page=30&page="+ap,deadline));
                        for(int j=0;j<batch.length();j++)assets.put(batch.getJSONObject(j));
                        if(batch.length()<30){all=true;break;}
                    }
                    if(!all)throw new IOException("附件列表未读完，无法确认更新。");
                }
                String base=ProjectLinks.REPOSITORY+"/releases/download/"+tag+"/";
                JSONObject meta=unique(assets,"android-update.json");
                if(!base.concat("android-update.json").equals(meta.optString("browser_download_url")))throw new IOException("Android 更新来源不符。");
                ReleaseMetadata candidate=ReleaseMetadata.parse((JSONObject)ReleaseJson.parse(transport.get(base+"android-update.json",deadline)),tag);
                JSONObject legacy=unique(assets,"ouc-autologin-"+candidate.name+"-release.apk");
                if(!candidate.apkUrl.equals(legacy.optString("browser_download_url")))throw new IOException("Android 安装附件来源不符。");
                String digest=legacy.optString("digest");if(!digest.isEmpty()&&!digest.equals("sha256:"+candidate.sha256))throw new IOException("Android 安装附件摘要不符。");
                // Older formal releases may only contain the legacy filename. New
                // release publishing enforces both brand and compatibility names.
                if(best==null||candidate.code>best.code)best=candidate;
            }
            if(releases.length()<30){complete=true;break;}
        }
        if(!complete)throw new IOException("发布列表尚未读完，无法确认最新版本，请打开发布页。");
        if(best==null)return new Value(false,"empty","尚无 Android 正式发布，可打开 GitHub 下载页查看预览版。",null,0);
        if(sdk<best.minSdk)return new Value(false,"incompatible","新版 "+best.name+" 要求更高 Android 系统版本。",best,0);
        return new Value(false,best.code>localCode?"available":best.code==localCode?"current":"ahead",
                best.code>localCode?"发现新版本 "+best.name:best.code==localCode?"当前已是最新 Android 正式版本":"当前版本高于发布源的 Android 正式版本",best,0);
    }
    private static JSONObject unique(JSONArray assets,String name)throws Exception{
        JSONObject found=null;
        for(int j=0;j<assets.length();j++){
            JSONObject a=assets.getJSONObject(j);if(!name.equals(a.optString("name")))continue;
            if(found!=null)throw new IOException("Android 发布不完整：附件重名 "+name);found=a;
        }
        if(found==null||!"uploaded".equals(found.optString("state","uploaded"))||found.optLong("size",1)<=0)
            throw new IOException("Android 发布不完整：缺少或尚未上传 "+name);
        return found;
    }
    static boolean allowed(URL url){
        if(!"https".equals(url.getProtocol())||url.getUserInfo()!=null||url.getRef()!=null||(url.getPort()!=-1&&url.getPort()!=443))return false;
        if(url.getHost().equals("api.github.com"))return url.getPath().equals("/repos/JieShenAI/OUNet/releases")||url.getPath().matches("/repos/JieShenAI/OUNet/releases/[0-9]+/assets");
        if(url.getHost().equals("github.com"))return url.getPath().matches("/JieShenAI/OUNet/releases/download/android-v[0-9]+\\.[0-9]+\\.[0-9]+/android-update\\.json");
        return url.getHost().equals("release-assets.githubusercontent.com");
    }
    private static String download(String value,long deadline)throws IOException{
        for(int hop=0;hop<4;hop++){
            URL url=new URL(value);if(!allowed(url))throw new IOException("更新地址不在允许范围。");long left=deadline-SystemClock.elapsedRealtime();if(left<=0)throw new IOException("更新检查超时，无法确认最新版本。");
            HttpURLConnection c=(HttpURLConnection)url.openConnection(java.net.Proxy.NO_PROXY);
            try{
                c.setConnectTimeout((int)Math.min(8000,left));c.setReadTimeout((int)Math.min(8000,left));c.setInstanceFollowRedirects(false);c.setRequestProperty("User-Agent","OUNet-Android/"+BuildConfig.VERSION_NAME);c.setRequestProperty("Accept","application/vnd.github+json");
                int status=c.getResponseCode();
                if(status==403||status==429){String retry=c.getHeaderField("Retry-After");throw new IOException("GitHub 拒绝请求或请求受限（HTTP "+status+"）"+(retry!=null&&retry.matches("[0-9]{1,8}")?"，建议 "+retry+" 秒后重试。":"，请稍后重试或打开发布页。"));}
                if(status==301||status==302||status==303||status==307||status==308){String location=c.getHeaderField("Location");if(location==null)throw new IOException("更新跳转无效。");value=new URL(url,location).toString();continue;}
                if(status!=200)throw new IOException("GitHub 返回 HTTP "+status+"，无法确认最新版本。");ByteArrayOutputStream out=new ByteArrayOutputStream();
                try(InputStream stream=c.getInputStream()){byte[] bytes=new byte[4096];int n;while(true){left=deadline-SystemClock.elapsedRealtime();if(left<=0)throw new IOException("更新检查超时，无法确认最新版本。");c.setReadTimeout((int)Math.min(8000,left));n=stream.read(bytes);if(n<0)break;if(out.size()+n>1000000)throw new IOException("更新响应过大。");out.write(bytes,0,n);}}
                return new String(out.toByteArray(),StandardCharsets.UTF_8);
            }finally{c.disconnect();}
        }
        throw new IOException("更新跳转过多。");
    }
}
