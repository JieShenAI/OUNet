package cn.jieshen.oucautologin;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** XHA is the product's service-availability criterion, not geographic proof. */
final class CampusDetector {
    static final class Result {final boolean campus;final String detail;Result(boolean campus,String detail){this.campus=campus;this.detail=detail;}}
    private String key="";private long checked=-1,next;private Result cached;
    synchronized Result detect(String connection,long now,boolean fresh,PortalClient.Transport transport)throws IOException{
        return detect(connection,now,fresh,false,transport);
    }
    synchronized Result detect(String connection,long now,boolean fresh,boolean forceNetwork,PortalClient.Transport transport)throws IOException{
        if(!connection.equals(key)){key=connection;checked=-1;next=0;cached=null;}
        if(!forceNetwork&&cached!=null&&now<next&&(!fresh||cached.campus&&now-checked<10000))return cached;
        try{
            PortalClient.Response response=transport.get(PortalClient.HOST);
            boolean valid=response.status==200&&portalPage(response.text);
            cached=new Result(valid,valid?"校园认证服务已确认":response.status!=200?"XHA返回HTTP "+response.status:"XHA门户内容未通过校验");
        }catch(RequestControl.Limited e){throw e;}
        catch(InterruptedIOException e){throw e;}
        catch(javax.net.ssl.SSLException e){cached=new Result(false,"XHA证书或加密连接校验失败");}
        catch(IOException e){cached=new Result(false,"XHA不可达，请检查网络或稍后重试");}
        checked=now;
        next=now+(cached.campus?60000:300);
        return cached;
    }
    synchronized void timeout(String connection,long now){key=connection;checked=now;next=now+300;cached=new Result(false,"XHA访问超时");}
    synchronized long nextDelay(long now){return Math.max(300,next-now);}
    static boolean portalPage(String body){
        String text=body.replaceAll("(?s)<!--.*?-->","").toLowerCase(Locale.ROOT);
        // A served login form already proves the authentication service is available.
        // Do not require a particular DrCOM version, asset name, or <html> wrapper.
        if(authenticationInputs(text))return true;
        // Current XHA also serves a JS shell whose inputs are created by a41.js.
        return text.contains("<html")&&text.matches("(?s).*<script[^>]+src\\s*=\\s*['\"](?:https://xha\\.ouc\\.edu\\.cn/|\\./|/)?a41\\.js(?:\\?[^'\"]*)?['\"].*")
            &&(text.contains("b80.css")&&text.contains("b82.css")||text.contains("drcom")||text.contains("v46ip")||text.contains("name=\"f1\"")||text.contains("name='f1'"));
    }
    private static final Pattern TAG=Pattern.compile("<(/?)(form|input)\\b((?:[^>\"']|\"[^\"]*\"|'[^']*')*)>");
    private static final Pattern ATTRIBUTE=Pattern.compile("([a-z_:][a-z0-9_.:-]*)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+)))?");
    private static boolean authenticationInputs(String text){
        String markup=text.replaceAll("(?s)<(script|style|template)\\b[^>]*>.*?</\\1\\s*>","");
        Matcher tags=TAG.matcher(markup);boolean account=false,password=false;
        while(tags.find()){
            if(tags.group(2).equals("form")){account=false;password=false;continue;}
            if(!tags.group(1).isEmpty())continue;
            Map<String,String> attributes=new HashMap<>();Matcher attr=ATTRIBUTE.matcher(tags.group(3));
            while(attr.find())attributes.put(attr.group(1),attr.group(2)!=null?attr.group(2):attr.group(3)!=null?attr.group(3):attr.group(4)!=null?attr.group(4):"");
            if(attributes.containsKey("disabled"))continue;
            String type=attributes.getOrDefault("type","text");
            if(type.equals("password"))password=true;
            else if(type.equals("text")||type.equals("email")||type.equals("tel")||type.equals("number"))account=true;
            if(account&&password)return true;
        }
        return false;
    }
}
