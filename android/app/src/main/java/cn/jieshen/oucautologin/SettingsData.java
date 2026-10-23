package cn.jieshen.oucautologin;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable settings; credentials always belong to a stable account ID. */
public final class SettingsData {
    public static final int DEFAULT_INTERVAL = 1;
    public static final class Account {
        public final String id, label, username, cipher, iv;
        public final long revision;
        public Account(String id, String label, String username, String cipher, String iv, long revision) {
            this.id=id; this.label=label; this.username=username; this.cipher=cipher; this.iv=iv; this.revision=revision;
        }
        public String display() {
            String masked = username.length()<4 ? "****" : username.substring(0,2)+"****"+username.substring(username.length()-2);
            return (label.isEmpty()?"校园网账号":label)+" · "+masked;
        }
    }
    public final List<Account> accounts;
    public final String activeId, target;
    public final int intervalSeconds, intervalMs;
    public final boolean enabled;
    public final long generation;
    public SettingsData(List<Account> accounts, String activeId, String target, int interval, boolean enabled, long generation) {
        this(accounts,activeId,target,Math.multiplyExact(interval,1000),enabled,generation,true);
    }
    private SettingsData(List<Account> accounts,String activeId,String target,int milliseconds,boolean enabled,long generation,boolean ms){
        validateMillis(milliseconds); if(!target.isEmpty()) validateSsid(target);
        if(generation<0 || accounts.size()>50) throw new IllegalArgumentException("配置数量或版本异常");
        Set<String> ids=new HashSet<>(), usernames=new HashSet<>();
        for(Account a:accounts) {
            if(a.id.isEmpty() || a.id.length()>80 || !ids.add(a.id) || !usernames.add(a.username)
                || a.username.isEmpty() || !a.username.equals(a.username.trim()) || a.username.length()>128
                || a.label.length()>40 || a.cipher.isEmpty() || a.iv.isEmpty() || a.revision<1) throw new IllegalArgumentException("账号配置异常");
        }
        if(!activeId.isEmpty()&&!ids.contains(activeId)) throw new IllegalArgumentException("选中账号不存在");
        if(enabled&&activeId.isEmpty()) throw new IllegalArgumentException("自动监测缺少账号");
        this.accounts=Collections.unmodifiableList(new ArrayList<>(accounts)); this.activeId=activeId; this.target=target;
        this.intervalMs=milliseconds;this.intervalSeconds=milliseconds/1000; this.enabled=enabled; this.generation=generation;
    }
    public static SettingsData empty(){return new SettingsData(Collections.emptyList(),"","",DEFAULT_INTERVAL,false,0);}
    public Account active(){return find(activeId);}
    public Account find(String id){for(Account a:accounts)if(a.id.equals(id))return a;return null;}
    public static void validateInterval(int seconds){if(seconds<1||seconds>3600)throw new IllegalArgumentException("旧版秒间隔需为1–3600之间的整数");}
    public static void validateMillis(int value){if(value<300||value>3600000)throw new IllegalArgumentException("状态评估间隔需为0.3–3600秒");}
    public static int parseSeconds(String text){
        try{java.math.BigDecimal n=new java.math.BigDecimal(text.trim());if(n.scale()>3)throw new IllegalArgumentException();int ms=n.movePointRight(3).intValueExact();validateMillis(ms);return ms;}
        catch(Exception e){throw new IllegalArgumentException("请输入0.3–3600秒，最多三位小数");}
    }
    public String intervalText(){return java.math.BigDecimal.valueOf(intervalMs,3).stripTrailingZeros().toPlainString();}
    public SettingsData changeMillis(List<Account> items,String active,String ssid,int millis,boolean monitor){return new SettingsData(items,active,ssid,millis,monitor,Math.addExact(generation,1),true);}
    public static void validateSsid(String value){
        if(value.isEmpty()||value.equals("<unknown ssid>")||value.indexOf('\0')>=0||value.getBytes(StandardCharsets.UTF_8).length>32)
            throw new IllegalArgumentException("Wi-Fi名称需为有效的1–32字节SSID");
    }
    public SettingsData change(List<Account> items,String active,String ssid,int interval,boolean monitor){
        return new SettingsData(items,active,ssid,interval,monitor,Math.addExact(generation,1));
    }
    public JSONObject json() throws org.json.JSONException {
        JSONArray array=new JSONArray();
        for(Account a:accounts)array.put(new JSONObject().put("id",a.id).put("label",a.label).put("username",a.username)
            .put("cipher",a.cipher).put("iv",a.iv).put("revision",a.revision));
        return new JSONObject().put("version",3).put("accounts",array).put("active_id",activeId).put("target_ssid",target)
            .put("interval_ms",intervalMs).put("enabled",enabled).put("generation",generation);
    }
    public static SettingsData parse(String text) throws Exception {
        if(text.length()>256000)throw new IllegalArgumentException("配置过大");
        JSONObject value=new JSONObject(text); if(value.getInt("version")!=2&&value.getInt("version")!=3)throw new IllegalArgumentException("配置版本不支持");
        JSONArray array=value.getJSONArray("accounts");List<Account> accounts=new ArrayList<>();
        for(int i=0;i<array.length();i++){JSONObject a=array.getJSONObject(i);accounts.add(new Account(a.getString("id"),a.getString("label"),a.getString("username"),a.getString("cipher"),a.getString("iv"),a.getLong("revision")));}
        return new SettingsData(accounts,value.getString("active_id"),value.optString("target_ssid",""),value.getInt("version")==2?Math.multiplyExact(value.getInt("interval_seconds"),1000):value.getInt("interval_ms"),value.getBoolean("enabled"),value.getLong("generation"),true);
    }
    public static SettingsData migrate(Map<String,?> old){
        String username=string(old,"username").trim(),cipher=string(old,"password_cipher"),iv=string(old,"password_iv");
        List<Account> accounts=new ArrayList<>();
        if(!username.isEmpty()&&!cipher.isEmpty()&&!iv.isEmpty())accounts.add(new Account("legacy-account-v1","原有账号",username,cipher,iv,1));
        String target=string(old,"ssid");
        if(accounts.isEmpty()&&target.equals("HUAWEI-Jie_5G"))target="";
        Object minutes=old.get("interval_minutes");int interval=minutes==null?DEFAULT_INTERVAL:Math.multiplyExact(((Number)minutes).intValue(),60);
        boolean monitor=Boolean.TRUE.equals(old.get("enabled"))&&!accounts.isEmpty();
        return new SettingsData(accounts,accounts.isEmpty()?"":accounts.get(0).id,target,interval,monitor,1);
    }
    private static String string(Map<String,?> values,String key){Object v=values.get(key);return v==null?"":(String)v;}
}
