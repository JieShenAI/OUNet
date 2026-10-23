package cn.jieshen.oucautologin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Reject duplicate object keys instead of accepting ambiguous release metadata. */
final class ReleaseJson extends JSONTokener {
    private ReleaseJson(String text){super(text);}
    static Object parse(String text)throws JSONException{
        ReleaseJson parser=new ReleaseJson(text);Object result=parser.nextValue();
        if(parser.nextClean()!=0)throw new JSONException("Trailing release data");return result;
    }
    @Override public Object nextValue()throws JSONException{
        char c=nextClean();
        if(c=='{'){
            JSONObject result=new JSONObject();c=nextClean();if(c=='}')return result;back();
            while(true){
                Object key=nextValue();if(!(key instanceof String))throw new JSONException("Object key");
                if(result.has((String)key))throw new JSONException("Duplicate release field");
                if(nextClean()!=':')throw new JSONException("Missing colon");result.put((String)key,nextValue());
                c=nextClean();if(c=='}')return result;if(c!=',')throw new JSONException("Object delimiter");
            }
        }
        if(c=='['){
            JSONArray result=new JSONArray();c=nextClean();if(c==']')return result;back();
            while(true){result.put(nextValue());c=nextClean();if(c==']')return result;if(c!=',')throw new JSONException("Array delimiter");}
        }
        back();return super.nextValue();
    }
}
