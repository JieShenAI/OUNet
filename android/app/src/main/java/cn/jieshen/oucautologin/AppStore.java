package cn.jieshen.oucautologin;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class AppStore {
    static final Object LOCK=new Object();
    private static final String KEY="ouc_portal_password_v1";
    private static final AtomicBoolean ANDROID_HEALTHY=new AtomicBoolean(true);
    private static final java.util.concurrent.atomic.AtomicLong LOG_REVISION=new java.util.concurrent.atomic.AtomicLong();
    interface Storage {Map<String,?> all(); boolean commit(Map<String,Object> updates);}
    interface Secrets {String[] encrypt(String password)throws Exception;String decrypt(SettingsData.Account account)throws Exception;}
    private final Context context;
    private final Storage storage;
    private final Secrets secrets;
    private final AtomicBoolean healthy;
    public AppStore(Context context){
        healthy=ANDROID_HEALTHY;
        this.context=context.getApplicationContext();
        SharedPreferences prefs=this.context.getSharedPreferences("ouc_local",Context.MODE_PRIVATE);
        storage=new Storage(){
            public Map<String,?> all(){return prefs.getAll();}
            public boolean commit(Map<String,Object> values){
                SharedPreferences.Editor e=prefs.edit();
                for(Map.Entry<String,Object> v:values.entrySet()){
                    Object x=v.getValue();
                    if(x==null)e.remove(v.getKey());else if(x instanceof String)e.putString(v.getKey(),(String)x);
                    else if(x instanceof Long)e.putLong(v.getKey(),(Long)x);else if(x instanceof Integer)e.putInt(v.getKey(),(Integer)x);
                    else if(x instanceof Boolean)e.putBoolean(v.getKey(),(Boolean)x);else throw new IllegalArgumentException("Unsupported setting");
                }return e.commit();
            }
        };
        secrets=new Secrets(){
            public String[] encrypt(String password)throws Exception{
                Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());
                return new String[]{Base64.encodeToString(c.doFinal(password.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP),Base64.encodeToString(c.getIV(),Base64.NO_WRAP)};
            }
            public String decrypt(SettingsData.Account a)throws Exception{
                Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(a.iv,Base64.NO_WRAP)));
                return new String(c.doFinal(Base64.decode(a.cipher,Base64.NO_WRAP)),StandardCharsets.UTF_8);
            }
        };
    }
    AppStore(Storage storage,Secrets secrets){context=null;this.storage=storage;this.secrets=secrets;healthy=new AtomicBoolean(true);}
    private void commit(Map<String,Object> updates)throws IOException{
        if(!storage.commit(updates)){healthy.set(false);throw new IOException("无法保存本地设置，已停止请求；请检查存储并重启应用");}
    }
    public boolean healthy(){return healthy.get();}
    private void put(String name,Object value)throws IOException{Map<String,Object> updates=new HashMap<>();updates.put(name,value);commit(updates);}
    private void save(SettingsData data)throws IOException{
        try{Map<String,Object> updates=new HashMap<>();updates.put("settings_v2",data.json().toString());updates.put("schema_version",3);commit(updates);}
        catch(org.json.JSONException e){throw new IOException("无法编码本地配置，已停止请求");}
    }
    public SettingsData config()throws IOException{
        synchronized(LOCK){
            Map<String,?> all=storage.all();
            try{
                if(all.containsKey("settings_v2")){String raw=(String)all.get("settings_v2");SettingsData parsed=SettingsData.parse(raw);if(new JSONObject(raw).getInt("version")==2)save(parsed);return parsed;}
                SettingsData migrated=SettingsData.migrate(all);save(migrated);return migrated;
            }catch(IOException e){throw e;}catch(Exception e){throw new IOException("本地配置异常，自动认证已停止；请在设置中修复配置");}
        }
    }
    public String ssid(){try{return config().target;}catch(IOException e){return "";}}
    public int intervalSeconds(){try{return config().intervalSeconds;}catch(IOException e){return SettingsData.DEFAULT_INTERVAL;}}
    public int intervalMs(){try{return config().intervalMs;}catch(IOException e){return 1000;}}
    public String intervalText(){try{return config().intervalText();}catch(IOException e){return "1";}}
    public boolean enabled(){try{return config().enabled;}catch(IOException e){return false;}}
    public boolean configured(){try{SettingsData c=config();return c.active()!=null;}catch(IOException e){return false;}}
    public boolean isCurrent(SettingsData expected){try{return healthy.get()&&config().generation==expected.generation;}catch(IOException e){return false;}}
    public void setEnabled(boolean value)throws IOException{synchronized(LOCK){SettingsData c=config();save(c.changeMillis(c.accounts,c.activeId,c.target,c.intervalMs,value));}}
    public void setTarget(String value)throws IOException{SettingsData.validateSsid(value);synchronized(LOCK){SettingsData c=config();save(c.changeMillis(c.accounts,c.activeId,value,c.intervalMs,c.enabled));}}
    public void setInterval(int value)throws IOException{setIntervalMs(Math.multiplyExact(value,1000));}
    public void setIntervalMs(int value)throws IOException{SettingsData.validateMillis(value);synchronized(LOCK){SettingsData c=config();save(c.changeMillis(c.accounts,c.activeId,c.target,value,c.enabled));}}
    public void selectAccount(String id)throws IOException{
        synchronized(LOCK){SettingsData c=config();if(c.find(id)==null)throw new IllegalArgumentException("请先保存或选择账号");save(c.changeMillis(c.accounts,id,c.target,c.intervalMs,c.enabled));}
    }
    public String saveAccount(String id,String label,String username,String password)throws Exception{
        synchronized(LOCK){
            SettingsData c=config();SettingsData.Account old=id==null?null:c.find(id);
            if(id!=null&&old==null)throw new IllegalArgumentException("账号已删除，请重新选择");
            username=username.trim();label=label.trim();
            if(username.isEmpty()||username.length()>128||label.length()>40)throw new IllegalArgumentException("请填写账号；账号最多128字符，备注最多40字符");
            if(old!=null&&!old.username.equals(username))throw new IllegalArgumentException("修改账号名请新增账号，原有账号名只读");
            for(SettingsData.Account a:c.accounts)if(a.username.equals(username)&&!a.id.equals(id))throw new IllegalArgumentException("该账号已保存，请从列表中编辑或选用");
            if(old==null&&password.isEmpty())throw new IllegalArgumentException("新增账号必须填写密码");
            String[] encrypted=password.isEmpty()?new String[]{old.cipher,old.iv}:secrets.encrypt(password);
            String accountId=old==null?UUID.randomUUID().toString():old.id;
            SettingsData.Account updated=new SettingsData.Account(accountId,label,username,encrypted[0],encrypted[1],old==null?1:Math.addExact(old.revision,1));
            List<SettingsData.Account> items=new ArrayList<>(c.accounts);if(old!=null)items.remove(old);items.add(updated);
            save(c.changeMillis(items,c.activeId,c.target,c.intervalMs,c.enabled));return accountId;
        }
    }
    public void deleteAccount(String id)throws IOException{
        synchronized(LOCK){SettingsData c=config();List<SettingsData.Account> items=new ArrayList<>(c.accounts);items.removeIf(a->a.id.equals(id));boolean active=c.activeId.equals(id);
            save(c.changeMillis(items,active?"":c.activeId,c.target,c.intervalMs,active?false:c.enabled));}
    }
    public String password(SettingsData.Account a)throws Exception{synchronized(LOCK){return secrets.decrypt(a);}}
    private SecretKey key()throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(KEY)){
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setUserAuthenticationRequired(false).build());generator.generateKey();
        }return(SecretKey)store.getKey(KEY,null);
    }
    public RetryPolicy retry()throws IOException{return retry("retry");}
    RetryPolicy transportRetry()throws IOException{return retry("transport_retry");}
    private RetryPolicy retry(String key)throws IOException{
        synchronized(LOCK){
            migrateRetry();Object raw=storage.all().get(key);if(raw==null||raw.equals(""))return RetryPolicy.empty();
            try{JSONObject state=new JSONObject((String)raw);int version=state.getInt("version");if(version!=2&&version!=3)throw new IllegalArgumentException();
                JSONArray array=state.getJSONArray("failures");List<Long> attempts=new ArrayList<>();for(int i=0;i<array.length();i++)attempts.add(array.getLong(i));
                RetryPolicy policy=version==2?new RetryPolicy(attempts,state.getBoolean("blocked")):new RetryPolicy(attempts,state.getBoolean("blocked"),state.getInt("level"),state.getLong("nextRetryAt"));
                long now=System.currentTimeMillis();
                if(policy.blocked&&policy.nextRetryAt>now+RetryPolicy.DELAYS_MS[policy.level-1]){
                    policy=new RetryPolicy(policy.failures,true,policy.level,now+RetryPolicy.DELAYS_MS[policy.level-1]);saveRetry(key,policy);
                }else if(version==2)saveRetry(key,policy);
                return policy;
            }catch(IOException e){throw e;}catch(Exception e){throw new IOException("失败保护记录异常，请排查后手动重置");}
        }
    }
    private void saveRetry(String key,RetryPolicy policy)throws IOException{
        try{put(key,new JSONObject().put("version",3).put("blocked",policy.blocked).put("failures",new JSONArray(policy.failures))
                .put("level",policy.level).put("nextRetryAt",policy.nextRetryAt).toString());}
        catch(org.json.JSONException e){throw new IOException("无法保存复检状态，停止请求");}
    }
    long recheckDelay()throws IOException{long now=System.currentTimeMillis();return Math.max(retry().remainingMillis(now),transportRetry().remainingMillis(now));}
    String recheckMessage()throws IOException{return "暂时暂停，"+Math.max(1,(recheckDelay()+999)/1000)+"秒后自动复检；恢复后继续守护";}
    void expediteRetry()throws IOException{
        synchronized(LOCK){long now=System.currentTimeMillis();Object raw=storage.all().get("retry_early_at");long last=raw instanceof Long?(Long)raw:0;
            RetryPolicy auth=retry(),network=transportRetry();if(!auth.blocked&&!network.blocked)return;
            if(now>=last&&now-last<15000)return;
            if(auth.blocked)saveRetry("retry",auth.expedite(now));if(network.blocked)saveRetry("transport_retry",network.expedite(now));
            put("retry_early_at",now);
        }
    }
    void connectionRecovered(SettingsData expected)throws IOException{
        synchronized(LOCK){if(!isCurrent(expected))throw new IOException("配置已变更");if(!storage.all().containsKey("retry")&&!storage.all().containsKey("transport_retry"))return;Map<String,Object> updates=new HashMap<>();updates.put("retry",null);updates.put("transport_retry",null);commit(updates);}
    }
    void resetTransportRetry(SettingsData expected)throws IOException{
        synchronized(LOCK){if(!isCurrent(expected))throw new IOException("配置已变更");if(storage.all().containsKey("transport_retry"))put("transport_retry",null);}
    }
    private void migrateRetry()throws IOException{
        Map<String,?> all=storage.all();if(Integer.valueOf(2).equals(all.get("retry_schema")))return;
        Map<String,Object> updates=new HashMap<>();
        if(all.get("retry")!=null)updates.put("retry_v1_history",all.get("retry"));
        if(all.get("last_auth_at")!=null)updates.put("auth_v1_history",all.get("last_auth_at"));
        updates.put("retry",null);updates.put("last_auth_at",null);updates.put("retry_schema",2);commit(updates);
    }
    public RetryPolicy recordAuthFailure(SettingsData expected,long now)throws IOException{return recordFailure("retry",expected,now);}
    void recordTransportFailure(SettingsData expected)throws IOException{recordFailure("transport_retry",expected,System.currentTimeMillis());}
    private RetryPolicy recordFailure(String key,SettingsData expected,long now)throws IOException{
        synchronized(LOCK){
            if(!isCurrent(expected))throw new IOException("配置已变更，取消旧轮认证");
            RetryPolicy previous=retry(key);
            if(!previous.canAttempt(now))throw new IOException("暂时暂停，等待自动复检");
            RetryPolicy next=previous.recordFailure(now);saveRetry(key,next);return next;
        }
    }
    private static String credentialId(SettingsData.Account account){
        try{byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest((account.cipher+"\n"+account.iv).getBytes(StandardCharsets.UTF_8));StringBuilder id=new StringBuilder();for(byte b:hash)id.append(String.format(Locale.ROOT,"%02x",b&255));return id.toString();}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    AuthError authError(SettingsData config){
        synchronized(LOCK){if(config.active()==null)return AuthError.NONE;Object raw=storage.all().get("credential_error");if(!(raw instanceof String))return AuthError.NONE;
            try{JSONObject error=new JSONObject((String)raw);if(error.getInt("version")!=1||!error.getString("account").equals(config.activeId)||!error.getString("credential").equals(credentialId(config.active())))return AuthError.NONE;return AuthError.valueOf(error.getString("kind"));}
            catch(Exception e){return AuthError.NONE;}}
    }
    void rememberAuthError(SettingsData config,AuthError error)throws IOException{
        synchronized(LOCK){if(!isCurrent(config)||config.active()==null)throw new IOException("账号已变化，不记录旧认证错误");
            try{put("credential_error",error==AuthError.NONE?null:new JSONObject().put("version",1).put("account",config.activeId).put("credential",credentialId(config.active())).put("kind",error.name()).toString());}
            catch(org.json.JSONException e){throw new IOException("无法保存认证错误提示");}}
    }
    public void resetRetry()throws IOException{synchronized(LOCK){migrateRetry();Map<String,?> all=storage.all();if(!all.containsKey("retry")&&!all.containsKey("last_auth_at")&&!all.containsKey("credential_error")&&!all.containsKey("transport_retry"))return;Map<String,Object> updates=new HashMap<>();updates.put("retry",null);updates.put("last_auth_at",null);updates.put("credential_error",null);updates.put("transport_retry",null);commit(updates);}}
    public void resetRetry(SettingsData expected)throws IOException{synchronized(LOCK){if(!isCurrent(expected))throw new IOException("配置已变更，不清除旧轮失败记录");resetRetry();}}
    public void repairSettings()throws IOException{
        synchronized(LOCK){Map<String,Object> updates=new HashMap<>();updates.put("settings_v2_recovery",storage.all().get("settings_v2"));
            try{SettingsData empty=SettingsData.empty();SettingsData recovered=new SettingsData(empty.accounts,"","",1,false,Math.max(1,System.currentTimeMillis()));
                updates.put("settings_v2",recovered.json().toString());updates.put("schema_version",3);commit(updates);}catch(org.json.JSONException e){throw new IOException("配置修复失败");}}
    }
    public String status(){Object v=storage.all().get("status");return v instanceof String?(String)v:"正在检测网络…";}
    public long lastCheck(){Object v=storage.all().get("last_check");return v instanceof Number?((Number)v).longValue():0;}
    public void checked(){try{put("last_check",System.currentTimeMillis());}catch(IOException ignored){}}
    public void status(String message){synchronized(LOCK){if(message.equals(status()))return;try{put("status",message);}catch(IOException ignored){}log(message);}}
    public void statusIfCurrent(SettingsData expected,String message){synchronized(LOCK){if(isCurrent(expected))status(message);}}
    public void log(String message){
        if(context==null)return;
        synchronized(LOCK){try{File file=new File(context.getFilesDir(),"events.log");String old=file.exists()?new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8):"";
            String line=new SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA).format(new Date())+"  "+message.replace('\n',' ')+"\n";
            String value=old+line;if(value.length()>24000)value=value.substring(value.indexOf('\n',value.length()-20000)+1);Files.write(file.toPath(),value.getBytes(StandardCharsets.UTF_8));LOG_REVISION.incrementAndGet();
        }catch(IOException ignored){}}
    }
    public String logs(){if(context==null)return "暂无运行记录";synchronized(LOCK){try{return new String(Files.readAllBytes(new File(context.getFilesDir(),"events.log").toPath()),StandardCharsets.UTF_8);}catch(IOException e){return "暂无运行记录";}}}
    public long logsRevision(){return LOG_REVISION.get();}
    static String newestFirst(String input){String[] lines=input.split("\n");StringBuilder result=new StringBuilder();for(int i=lines.length-1;i>=0;i--)if(!lines[i].isEmpty()){if(result.length()>0)result.append('\n');result.append(lines[i]);}return result.length()==0?"暂无运行记录":result.toString();}
    public String logsNewestFirst(){return newestFirst(logs());}
}
