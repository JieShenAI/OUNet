package cn.jieshen.oucautologin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReleaseLookupTest {
    private JSONObject metadata(int code,String version)throws Exception{
        String base=ProjectLinks.REPOSITORY+"/releases/";
        return new JSONObject().put("applicationId",ReleaseMetadata.APP_ID).put("versionCode",code).put("versionName",version)
                .put("minSdk",28).put("releaseUrl",base+"tag/android-v"+version)
                .put("apkUrl",base+"download/android-v"+version+"/ouc-autologin-"+version+"-release.apk")
                .put("sha256",new String(new char[64]).replace('\0','a')).put("signingCertSha256",ReleaseMetadata.CERT);
    }
    private JSONObject release(String version)throws Exception{
        String base=ProjectLinks.REPOSITORY+"/releases/download/android-v"+version+"/";
        return new JSONObject().put("tag_name","android-v"+version).put("assets",new JSONArray()
                .put(new JSONObject().put("name","android-update.json").put("browser_download_url",base+"android-update.json"))
                .put(new JSONObject().put("name","ouc-autologin-"+version+"-release.apk").put("browser_download_url",base+"ouc-autologin-"+version+"-release.apk")));
    }
    private UpdateRepository.Value lookup(List<String> replies,int local,int sdk)throws Exception{
        return UpdateRepository.lookup((url,deadline)->replies.remove(0),()->100L,local,sdk);
    }
    private List<String> replies(Object... objects){List<String> values=new ArrayList<>();for(Object o:objects)values.add(o.toString());return values;}
    @Test public void discoversNewVersionAndDoesNotOfferDowngrades()throws Exception{
        for(int local:new int[]{8,9,10}){
            UpdateRepository.Value result=lookup(replies(new JSONArray().put(release("1.3.5")),metadata(9,"1.3.5")),local,35);
            assertEquals(local==8?"available":local==9?"current":"ahead",result.state);
        }
    }
    @Test public void pagesPastMixedPlatformReleases()throws Exception{
        JSONArray first=new JSONArray();for(int i=0;i<30;i++)first.put(new JSONObject().put("tag_name","windows-v0.3."+i));
        List<String> values=replies(first,new JSONArray().put(release("1.3.5")),metadata(9,"1.3.5"));
        assertEquals("available",lookup(values,8,35).state);assertTrue(values.isEmpty());
    }
    @Test public void exhaustionNeverClaimsCurrent()throws Exception{
        JSONArray page=new JSONArray();for(int i=0;i<30;i++)page.put(new JSONObject().put("tag_name","windows-v0.3."+i));
        List<String> values=new ArrayList<>();for(int i=0;i<10;i++)values.add(page.toString());
        try{lookup(values,9,35);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("无法确认"));}
    }
    @Test public void excludesDraftPreviewAndOtherPlatforms()throws Exception{
        JSONArray releases=new JSONArray().put(release("1.3.5").put("draft",true)).put(release("1.3.5").put("prerelease",true))
                .put(new JSONObject().put("tag_name","windows-v99.0.0"));
        assertEquals("empty",lookup(replies(releases),8,35).state);
    }
    @Test public void missingOrDuplicateInstallerNeverCreatesAnUpdate()throws Exception{
        for(boolean duplicate:new boolean[]{false,true}){
            JSONObject r=release("1.3.5");JSONArray assets=r.getJSONArray("assets");
            if(duplicate)assets.put(assets.getJSONObject(1));else assets.remove(1);
            try{lookup(replies(new JSONArray().put(r),metadata(9,"1.3.5")),8,35);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("不完整"));}
        }
    }
    @Test public void minSdkAndHigherCodeAcrossNineToTenAreHandled()throws Exception{
        assertEquals("available",lookup(replies(new JSONArray().put(release("1.3.6")),metadata(10,"1.3.6")),9,35).state);
        assertEquals("incompatible",lookup(replies(new JSONArray().put(release("1.3.5")),metadata(9,"1.3.5").put("minSdk",36)),8,35).state);
    }
    @Test public void ambiguousMetadataAndUntrustedAssetsAreRejected()throws Exception{
        try{ReleaseJson.parse("{\"versionCode\":9,\"versionCode\":10}");fail();}catch(org.json.JSONException expected){}
        JSONObject r=release("1.3.5");r.getJSONArray("assets").getJSONObject(1).put("browser_download_url","https://example.test/setup.apk");
        try{lookup(replies(new JSONArray().put(r),metadata(9,"1.3.5")),8,35);fail();}catch(IOException expected){}
    }
    @Test public void highestCodeWinsEvenIfReleaseListIsNotVersionOrdered()throws Exception{
        JSONArray list=new JSONArray().put(release("1.3.5")).put(release("1.3.6"));
        assertEquals(10,lookup(replies(list,metadata(9,"1.3.5"),metadata(10,"1.3.6")),8,35).release.code);
    }
}
