package cn.jieshen.oucautologin;

import java.net.URL;

final class RequestPolicy {
    private RequestPolicy(){}
    static boolean allowed(URL url){
        if(url.getUserInfo()!=null||url.getRef()!=null)return false;
        if("http".equals(url.getProtocol())&&(url.getPort()==-1||url.getPort()==80)&&url.getQuery()==null)
            return ("www.msftconnecttest.com".equals(url.getHost())&&"/connecttest.txt".equals(url.getPath()))
                ||("captive.apple.com".equals(url.getHost())&&"/hotspot-detect.html".equals(url.getPath()))
                ||("firefox-portal-detection.com".equals(url.getHost())&&"/success.txt".equals(url.getPath()));
        if(!"https".equals(url.getProtocol())||!"xha.ouc.edu.cn".equals(url.getHost())||url.getPort()==0||url.getPort()>65535)return false;
        String path=url.getPath();return path.equals("/")||path.equals("/a79.htm")||path.equals("/a41.js")||path.equals("/eportal/portal/page/loadConfig")||path.equals("/eportal/portal/login")
            ||path.equals("/drcom/chkstatus")||path.equals("/eportal/portal/page/loadOnlineRecord");
    }
}
