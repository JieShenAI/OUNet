package cn.jieshen.oucautologin;

import java.io.IOException;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class DeviceAndReleaseTest {
    private JSONObject row(int n)throws Exception{return new JSONObject().put("session_id","session-"+n).put("login_ip","192.0.2."+n).put("phone_flag",n%2).put("login_time","2026-10-23 08:00:00");}
    @Test public void devicePaginationContinuesBeyondOneHundredAndMarksOnlyFinalPageComplete()throws Exception{
        int count=0;for(int start=1;start<=121;start+=20){JSONArray records=new JSONArray();for(int n=start;n<Math.min(122,start+20);n++)records.put(row(n));SessionInfoClient.Page page=SessionInfoClient.devicePage("real-user","192.0.2.1","",start,new JSONObject().put("count",121).put("records",records));count+=page.devices.size();assertEquals(start+records.length(),page.next);assertEquals(start==121,page.complete);}assertEquals(121,count);
    }
    @Test public void unknownTotalUsesShortPageAndEmptyIncompletePageFails()throws Exception{
        assertTrue(SessionInfoClient.devicePage("u","","",1,new JSONObject().put("records",new JSONArray())).complete);
        try{SessionInfoClient.devicePage("u","","",1,new JSONObject().put("count",20).put("records",new JSONArray()));fail();}catch(IOException expected){}
    }
    @Test public void otherAccountAndWrongOwnerAreNotLabeledAsThisDevice()throws Exception{
        JSONObject different=row(2).put("is_owner_ip",1);assertFalse(SessionInfoClient.devicePage("u","192.0.2.1","",1,new JSONObject().put("records",new JSONArray().put(different))).devices.get(0).owner);
        different.put("uid","other");try{SessionInfoClient.devicePage("u","","",1,new JSONObject().put("records",new JSONArray().put(different)));fail();}catch(IOException expected){}
    }
    private JSONObject metadata()throws Exception{return new JSONObject().put("applicationId",ReleaseMetadata.APP_ID).put("versionCode",4).put("versionName","1.3.0").put("minSdk",28).put("releaseUrl",ReleaseMetadata.REPO+"/releases/tag/android-v1.3.0").put("apkUrl",ReleaseMetadata.REPO+"/releases/download/android-v1.3.0/ouc-autologin-1.3.0-release.apk").put("sha256",new String(new char[64]).replace('\0','a')).put("signingCertSha256",ReleaseMetadata.CERT);}
    @Test public void updateRequiresExactAndroidTagPackageSignerAndSource()throws Exception{
        assertEquals(4,ReleaseMetadata.parse(metadata(),"android-v1.3.0").code);
        for(String field:new String[]{"applicationId","signingCertSha256","releaseUrl","apkUrl","sha256"}){JSONObject value=metadata().put(field,"wrong");try{ReleaseMetadata.parse(value,"android-v1.3.0");fail(field);}catch(IOException expected){}}
        for(String tag:new String[]{"windows-v0.2.0","v1.3.0","android-v1.3.0-debug"})try{ReleaseMetadata.parse(metadata(),tag);fail(tag);}catch(IOException expected){}
    }
    @Test public void githubPolicyExcludesArbitraryHostsAndDowngrades()throws Exception{
        assertTrue(UpdateRepository.allowed(new URL("https://api.github.com/repos/JieShenAI/OUNet/releases?page=1")));
        for(String url:new String[]{"http://github.com/JieShenAI/OUNet","https://evil.example/update.json","https://github.com/other/repo/releases/download/android-v1.3.0/android-update.json","https://user@api.github.com/repos/JieShenAI/OUNet/releases"})assertFalse(UpdateRepository.allowed(new URL(url)));
    }
}
