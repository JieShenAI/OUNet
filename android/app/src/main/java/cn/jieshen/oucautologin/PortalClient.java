package cn.jieshen.oucautologin;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Protocol only. Every request uses the same Wi-Fi-bound transport. */
public final class PortalClient {
    public static final String HOST = "https://xha.ouc.edu.cn/";
    public interface Transport { Response get(String url) throws IOException; }
    public static final class Response {
        public final int status;
        public final String text;
        public Response(int status, String text) { this.status = status; this.text = text; }
    }
    private final Transport transport;
    public PortalClient(Transport transport) { this.transport = transport; }

    public enum ProbeState { ONLINE, CAPTIVE, UNREACHABLE, UNKNOWN }
    public static final class Probe {
        public final ProbeState state;
        public final String detail;
        Probe(ProbeState state,String detail){this.state=state;this.detail=detail;}
        public boolean online(){return state==ProbeState.ONLINE;}
    }
    public boolean hasInternet() {
        try{return probe().online();}catch(IOException e){return false;}
    }
    public Probe probe() throws IOException {
        String[] urls = {"http://www.msftconnecttest.com/connecttest.txt", "http://captive.apple.com/hotspot-detect.html"};
        boolean captive=false, responded=false;
        String problem="探测端点不可达";
        for (int i = 0; i < urls.length; i++) {
            try {
                Response response = transport.get(urls[i]);
                responded=true;
                if (response.status>=300&&response.status<400)captive=true;
                if (response.status != 200) {problem="探测返回HTTP "+response.status;continue;}
                String text = response.text.trim();
                if (i == 0 && text.equals("Microsoft Connect Test")) return new Probe(ProbeState.ONLINE,"Wi-Fi外网探测通过");
                if (i == 1 && Pattern.compile("<title>\\s*Success\\s*</title>", Pattern.CASE_INSENSITIVE).matcher(text).find()
                    && Pattern.compile("<body>\\s*Success\\s*</body>", Pattern.CASE_INSENSITIVE).matcher(text).find()) return new Probe(ProbeState.ONLINE,"Wi-Fi外网探测通过");
                problem="探测正文不符合预期，可能需要认证";
            } catch (java.io.InterruptedIOException error) {
                if(error instanceof java.net.SocketTimeoutException){problem="外网探测超时";continue;}
                throw error;
            } catch (java.net.UnknownHostException error) { problem="Wi-Fi DNS解析失败";
            } catch (IOException error) { problem="外网探测连接失败"; }
        }
        return new Probe(captive?ProbeState.CAPTIVE:responded?ProbeState.UNKNOWN:ProbeState.UNREACHABLE,
            captive?"探测遭重定向，网络可能需要认证":problem);
    }

    String text(String url, String stage) throws IOException {
        Response response;
        try { response = transport.get(url); }
        catch (java.io.InterruptedIOException error) {
            if(error instanceof java.net.SocketTimeoutException)throw new IOException(stage+"：请求超时");
            throw error;
        }
        catch (RequestControl.Limited error) {throw error;}
        catch (javax.net.ssl.SSLException error) { throw new IOException(stage + "：HTTPS 证书或加密连接失败"); }
        catch (IOException error) { throw new IOException(stage + "：连接失败，请检查校园 Wi-Fi 和 DNS"); }
        if (response.status != 200) throw new IOException(stage + "：HTTP " + response.status);
        return response.text;
    }

    public static JSONObject parseJsonp(String body) throws IOException {
        String text = body.replaceFirst("^\\uFEFF", "").trim();
        if (!text.startsWith("{")) {
            Matcher matcher = Pattern.compile("[A-Za-z_$][\\w$]*\\s*\\((.*)\\)\\s*;?", Pattern.DOTALL).matcher(text);
            if (!matcher.matches()) throw new IOException("认证接口返回格式错误");
            text = matcher.group(1);
        }
        try {
            JSONTokener tokens = new JSONTokener(text);
            Object value = tokens.nextValue();
            if (!(value instanceof JSONObject) || tokens.nextClean() != 0) throw new IOException("认证接口返回格式错误");
            return (JSONObject) value;
        }
        catch (Exception error) { throw new IOException("认证接口返回格式错误"); }
    }

    public static String jsString(String source, String name, String fallback) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*(['\"])(.*?)\\1").matcher(source);
        return matcher.find() ? matcher.group(2).trim() : fallback;
    }

    public static String query(Map<String, String> fields) {
        StringBuilder value = new StringBuilder();
        for (Map.Entry<String, String> item : fields.entrySet()) {
            if (value.length() > 0) value.append('&');
            try { value.append(URLEncoder.encode(item.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(item.getValue(), "UTF-8")); }
            catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
        }
        return value.toString();
    }

    JSONObject json(String url, Map<String, String> fields, String stage) throws IOException {
        fields.put("callback", "dr1003"); fields.put("jsVersion", "4.1"); fields.put("lang", "zh-cn");
        fields.put("v", Integer.toString(ThreadLocalRandom.current().nextInt(500, 10500)));
        return parseJsonp(text(url + "?" + query(fields), stage));
    }

    static final class ContextInfo {
        final String api, program, ip, mac; final JSONObject data;
        ContextInfo(String api,String program,String ip,String mac,JSONObject data){this.api=api;this.program=program;this.ip=ip;this.mac=mac;this.data=data;}
    }
    ContextInfo discover() throws IOException {
        String page = text(HOST + "a79.htm", "读取认证页面");
        String script = text(HOST + "a41.js", "读取认证脚本");
        Matcher portMatch = Pattern.compile("\\benHTTPSPort\\s*=\\s*(\\d+)\\b").matcher(script);
        Matcher program = Pattern.compile("\\bname\\s*:\\s*['\"]([\\w-]+)['\"]").matcher(script);
        if (!portMatch.find() || !program.find()) throw new IOException("学校认证配置已变化，缺少端口或方案名称");
        int port;
        try { port = Integer.parseInt(portMatch.group(1)); } catch (NumberFormatException error) { throw new IOException("认证端口无效"); }
        if (port < 1 || port > 65535) throw new IOException("认证端口无效");
        String api = "https://xha.ouc.edu.cn:" + port + "/eportal/portal/";
        String ip = jsString(page, "v46ip", jsString(page, "ss5", jsString(page, "v4ip", "0.0.0.0")));
        if (!validIpv4(ip)) ip = "0.0.0.0";
        String mac = jsString(page, "ss4", "000000000000").replace(":", "").replace("-", "");
        if (!mac.matches("[0-9a-fA-F]{12}")) mac = "000000000000";
        Map<String, String> config = new LinkedHashMap<>();
        config.put("program_index", program.group(1)); config.put("wlan_vlan_id", jsString(page, "vlanid", "1"));
        config.put("wlan_user_ip", ip); config.put("wlan_user_ipv6", ""); config.put("wlan_user_ssid", "");
        config.put("wlan_user_areaid", ""); config.put("wlan_ac_ip", ""); config.put("wlan_ap_mac", "000000000000"); config.put("gw_id", "000000000000");
        JSONObject data = json(api + "page/loadConfig", config, "读取认证配置").optJSONObject("data");
        if (data == null) throw new IOException("认证配置缺少 data 字段");
        return new ContextInfo(api,program.group(1),ip,mac,data);
    }
    public static final class AuthenticationResult {
        public final boolean accepted,refused; public final String message;
        final AuthError error;
        AuthenticationResult(boolean accepted,boolean refused,String message){this(accepted,refused,message,AuthError.NONE);}
        AuthenticationResult(boolean accepted,boolean refused,String message,AuthError error){this.accepted=accepted;this.refused=refused;this.message=message;this.error=error;}
    }
    public String login(String username,String password)throws IOException{return authenticate(username,password).message;}
    public AuthenticationResult authenticate(String username,String password)throws IOException {
        return authenticate(username,password,discover());
    }
    AuthenticationResult authenticate(String username,String password,ContextInfo context)throws IOException {
        JSONObject data=context.data;
        String api=context.api,ip=context.ip,mac=context.mac;
        int method = data.optInt("login_method", -1);
        if (method != 1 && method != 9) throw new IOException("学校当前认证方式不受支持，已停止登录");
        String account = (data.optInt("account_prefix", 0) == 1 ? ",1," : "") + username + data.optString("account_suffix", "");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("login_method", Integer.toString(method)); fields.put("user_account", account); fields.put("user_password", password);
        fields.put("wlan_user_ip", ip); fields.put("wlan_user_ipv6", ""); fields.put("wlan_user_mac", mac);
        fields.put("wlan_ac_ip", ""); fields.put("wlan_ac_name", ""); fields.put("terminal_type", "2");
        JSONObject result = json(api + "login", fields, "提交校园网登录");
        String rawMessage=result.optString("msg", "未提供错误说明");
        String message = redact(rawMessage, username, password);
        if ("1".equals(result.optString("result")) || "ok".equals(result.optString("result"))) return new AuthenticationResult(true,false,"认证成功，正在复检");
        boolean already=result.optInt("ret_code",-1)==2;
        if (already&&(message.contains("已在线")||message.contains("已经在线")||message.toLowerCase(Locale.ROOT).contains("already online")))return new AuthenticationResult(false,false,"服务器提示当前 IP 已在线，正在复检");
        boolean refused=!already&&"0".equals(result.optString("result"));
        AuthError error=refused?AuthError.fromMessage(rawMessage):AuthError.NONE;
        return new AuthenticationResult(false,refused,error==AuthError.NONE?"服务器未确认登录："+message:error.guidance(),error);
    }

    public static boolean validIpv4(String ip) {
        if (!ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        for (String part : ip.split("\\.")) if (Integer.parseInt(part) > 255) return false;
        return true;
    }

    public static String redact(String text, String username, String password) {
        for (String secret : new String[]{password, username}) {
            if (secret.isEmpty()) continue;
            text = text.replace(secret, "[已隐藏]");
            try { text = text.replace(URLEncoder.encode(secret, "UTF-8"), "[已隐藏]"); }
            catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
        }
        text = text.replaceAll("https?://\\S+", "[链接已隐藏]").replaceAll("\\s+", " ");
        return text.substring(0, Math.min(200, text.length()));
    }
}
