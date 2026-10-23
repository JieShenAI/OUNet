package cn.jieshen.oucautologin;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only adapter for the public a41/a43 portal contract. No local credentials. */
final class SessionInfoClient {
    static final String MISSING="页面未提供";
    static final class Info {
        final String account,since,ipv4,ipv6,mac,mobile;
        final long fetchedAt;
        Info(String account,String since,String ipv4,String ipv6,String mac,String mobile){this.account=account;this.since=since;this.ipv4=ipv4;this.ipv6=ipv6;this.mac=mac;this.mobile=mobile;fetchedAt=System.currentTimeMillis();}
        String[] fields(){return new String[]{account,since,ipv4,ipv6,mac,mobile};}
    }
    private PortalClient client;
    private PortalClient.ContextInfo deviceContext;
    private long contextAt;
    enum Auth { ONLINE, UNAUTHENTICATED, UNKNOWN }
    static final class Status {
        final Auth auth;final String account;final PortalClient.ContextInfo context;final JSONObject data;
        Status(Auth auth,String account,PortalClient.ContextInfo context,JSONObject data){this.auth=auth;this.account=account;this.context=context;this.data=data;}
        Info summary(){String mac=normalizeMac(clean(data.opt("ss4"),64));return new Info(missing(account),MISSING,missing(sourceIp(data)),MISSING,missing(mac),MISSING);}
    }
    static Status status(PortalClient.ContextInfo context,JSONObject data){
        String result=data.optString("result","");
        return new Status(result.equals("1")?Auth.ONLINE:result.equals("0")?Auth.UNAUTHENTICATED:Auth.UNKNOWN,clean(data.opt("uid"),128),context,data);
    }
    Status readStatus()throws IOException{
        PortalClient.ContextInfo c=client.discover();
        if(!"0".equals(c.data.optString("check_online_method","0")))throw new IOException("当前会话读取模式尚未验证");
        return status(c,client.json(PortalClient.HOST+"drcom/chkstatus",new LinkedHashMap<>(),"读取当前在线状态"));
    }
    SessionInfoClient(PortalClient.Transport transport){client=new PortalClient(transport);}
    void transportForDevices(PortalClient.Transport transport){client=new PortalClient(transport);}
    static final class Device {
        final Info info;final boolean owner;final String id;
        Device(Info info,boolean owner,String id){this.info=info;this.owner=owner;this.id=id;}
    }
    static final class Page {
        final String account;final List<Device> devices;final int next,total;final boolean complete;
        Page(String account,List<Device> devices,int next,int total,boolean complete){this.account=account;this.devices=devices;this.next=next;this.total=total;this.complete=complete;}
    }
    static final class IdentityChanged extends IOException {IdentityChanged(){super("在线账号已变化，请重新刷新");}}
    Page devices(int start,long now)throws IOException{
        if(start<1)throw new IOException("设备分页范围无效");
        if(deviceContext==null||now-contextAt>=30000){deviceContext=client.discover();contextAt=now;}
        PortalClient.ContextInfo c=deviceContext;
        if(c.data.has("check_online_method")&&!"0".equals(c.data.optString("check_online_method")))throw new IOException("当前会话读取模式尚未验证");
        JSONObject status=client.json(PortalClient.HOST+"drcom/chkstatus",new LinkedHashMap<>(),"读取在线账号");String account=clean(status.opt("uid"),128);
        if(!"1".equals(status.optString("result"))||account.isEmpty())throw new IOException("当前未取得在线账号");
        String ip=sourceIp(status);if(ip.isEmpty())ip=validAddress(c.ip,false);
        String mac=normalizeMac(clean(status.opt("ss4"),64));if(mac.isEmpty())mac=normalizeMac(c.mac);
        String page=clean(c.data.opt("page_index"),128);if(page.isEmpty())throw new IOException("在线详情页面配置缺失");
        Map<String,String> fields=new LinkedHashMap<>();fields.put("program_index",c.program);fields.put("page_index",page);fields.put("user_account",account);fields.put("wlan_user_ip",ip);fields.put("wlan_user_mac",mac);
        fields.put("start_time","2010-01-01");fields.put("end_time","2100-01-01");fields.put("start_rn",Integer.toString(start));fields.put("end_rn",Integer.toString(Math.addExact(start,19)));
        JSONObject response=client.json(c.api+"page/loadOnlineRecord",fields,"读取在线设备");return devicePage(account,ip,mac,start,response);
    }
    static Page devicePage(String account,String ip,String mac,int start,JSONObject response)throws IOException{
        JSONArray rows=response.optJSONArray("records");if(rows==null||rows.length()>100)throw new IOException("设备列表格式变化");
        int total=response.has("count")?response.optInt("count",-1):-1;if(total< -1||(response.has("count")&&total<0))throw new IOException("设备总数无效");
        List<Device> devices=new ArrayList<>();
        for(int i=0;i<rows.length();i++){
            JSONObject r=rows.optJSONObject(i);if(r==null)throw new IOException("设备列表格式变化");
            for(String field:new String[]{"uid","user_account"}){String value=clean(r.opt(field),128);if(!value.isEmpty()&&!value.equals(account))throw new IdentityChanged();}
            String addr=validAddress(clean(r.opt("login_ip"),64),false),m=normalizeMac(clean(r.opt("mac_address"),64)),flag=clean(r.opt("phone_flag"),32);
            String mobile=flag.isEmpty()?MISSING:flag.matches("[0-9]{1,3}")?(flag.equals("0")?"PC端":"移动端")+"（"+flag+"）":"未识别（"+flag+"）";
            Info info=new Info(account,field(r,"login_time"),missing(addr),missing(validAddress(clean(r.opt("login_ip_v6"),128),true)),m.isEmpty()?MISSING:field(r,"mac_address"),mobile);
            boolean owner=("1".equals(r.optString("is_owner_ip"))||(!ip.isEmpty()&&ip.equals(addr)))&&!( !ip.isEmpty()&&!addr.isEmpty()&&!ip.equals(addr))&&!( !mac.isEmpty()&&!m.isEmpty()&&!mac.equals(m));
            String id=clean(r.opt("session_id"),128);if(id.isEmpty())id=addr+"|"+info.ipv6+"|"+m+"|"+info.since+"|"+flag;
            devices.add(new Device(info,owner,id));
        }
        int next=Math.addExact(start,rows.length());boolean complete=total>=0?next-1>=total:rows.length()<20;
        if(rows.length()==0&&total>=start)throw new IOException("分页未取得全部记录，请稍后刷新");
        return new Page(account,devices,next,total,complete);
    }
    Info fetch()throws IOException {
        return fetch(readStatus());
    }
    Info fetch(Status current)throws IOException {
        PortalClient.ContextInfo c=current.context;JSONObject status=current.data;String account=current.account;
        if(current.auth!=Auth.ONLINE||account.isEmpty())throw new IOException("当前未取得校园网在线账号，不能读取详情");
        String ip=sourceIp(status);if(ip.isEmpty())ip=validAddress(c.ip,false);
        String mac=normalizeMac(clean(status.opt("ss4"),64));
        if(mac.isEmpty())mac=normalizeMac(clean(status.opt("olmac"),64));
        if(mac.isEmpty())mac=normalizeMac(c.mac);
        String page=clean(c.data.opt("page_index"),128);
        if(page.isEmpty())throw new IOException("在线详情页面配置缺失");
        Map<String,String> fields=new LinkedHashMap<>();
        fields.put("program_index",c.program);fields.put("page_index",page);fields.put("user_account",account);
        fields.put("wlan_user_ip",ip);fields.put("wlan_user_mac",mac);fields.put("start_time","2010-01-01");fields.put("end_time","2100-01-01");fields.put("start_rn","1");fields.put("end_rn","100");
        JSONObject response=client.json(c.api+"page/loadOnlineRecord",fields,"读取在线会话详情");
        JSONArray records=response.optJSONArray("records");
        if(records==null||records.length()>100)throw new IOException("在线详情格式变化");
        return select(account,ip,mac,records);
    }
    static Info select(String account,String ip,String mac,JSONArray records)throws IOException {
        List<JSONObject> owners=new ArrayList<>(),matches=new ArrayList<>();
        for(int i=0;i<records.length();i++){
            JSONObject r=records.optJSONObject(i);if(r==null)throw new IOException("在线详情格式变化");
            if("1".equals(r.optString("is_owner_ip")))owners.add(r);
            if(!ip.isEmpty()&&ip.equals(validAddress(clean(r.opt("login_ip"),64),false)))matches.add(r);
        }
        List<JSONObject> selected=owners.isEmpty()?matches:owners;
        if(selected.size()>1&&!mac.isEmpty()){
            List<JSONObject> exact=new ArrayList<>();for(JSONObject r:selected)if(mac.equals(normalizeMac(clean(r.opt("mac_address"),64))))exact.add(r);selected=exact;
        }
        if(selected.size()!=1)throw new IOException("无法唯一确认当前设备的在线记录");
        JSONObject r=selected.get(0);
        for(String field:new String[]{"uid","user_account"}){String value=clean(r.opt(field),128);if(!value.isEmpty()&&!account.equals(value))throw new IOException("在线记录账号不一致");}
        String recordIp=validAddress(clean(r.opt("login_ip"),64),false),recordMac=normalizeMac(clean(r.opt("mac_address"),64));
        if((!ip.isEmpty()&&!recordIp.isEmpty()&&!ip.equals(recordIp))||(!mac.isEmpty()&&!recordMac.isEmpty()&&!mac.equals(recordMac)))throw new IOException("当前设备地址与在线记录不一致");
        String phone=clean(r.opt("phone_flag"),32);
        String mobile=phone.isEmpty()?MISSING:phone.matches("[0-9]{1,3}")?("0".equals(phone)?"PC端":"移动端")+"（"+phone+"）":"未识别（"+phone+"）";
        return new Info(account,field(r,"login_time"),missing(recordIp),missing(validAddress(clean(r.opt("login_ip_v6"),128),true)),recordMac.isEmpty()?MISSING:field(r,"mac_address"),mobile);
    }
    static String clean(Object value,int limit){
        if(value==null||value==JSONObject.NULL||value instanceof JSONObject||value instanceof JSONArray)return "";
        String text=String.valueOf(value).replaceAll("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]","").trim();
        return text.length()>limit?"":text;
    }
    private static String field(JSONObject r,String key){return missing(clean(r.opt(key),256));}
    private static String missing(String value){return value.isEmpty()?MISSING:value;}
    static String normalizeMac(String value){String m=value.replace(":","").replace("-","").toLowerCase(java.util.Locale.ROOT);return m.matches("[0-9a-f]{12}")&&!m.equals("000000000000")&&!m.equals("111111111111")?m:"";}
    static String sourceIp(JSONObject data){for(String key:new String[]{"v46ip","ss5","v4ip"}){String ip=validAddress(clean(data.opt(key),64),false);if(!ip.isEmpty())return ip;}return "";}
    static String validAddress(String text,boolean ipv6){
        if(!ipv6)return PortalClient.validIpv4(text)&&!"0.0.0.0".equals(text)?text:"";
        // Character restriction prevents hostname lookup by InetAddress.
        if(!text.contains(":")||!text.matches("[0-9a-fA-F:.]{2,64}"))return "";
        try{InetAddress address=InetAddress.getByName(text);return !address.isAnyLocalAddress()&&address.getAddress().length==16?text:"";}catch(Exception e){return "";}
    }
}
