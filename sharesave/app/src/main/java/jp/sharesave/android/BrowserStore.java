package jp.sharesave.android;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Local-only, private WebView bookmarks/history. Never synchronizes browsing
 * records to a server or embeds media URLs in the app's long-term records.
 */
final class BrowserStore {
    private static final String FILE="sharesave_browser";
    private static final String BOOKMARKS="bookmarks_v1",HISTORY="history_v1",HOME="homepage_v1";
    private static final int MAX_BOOKMARKS=250, MAX_HISTORY=200;
    static final String DEFAULT_HOME="https://www.google.com/";
    static final class Record {
        final String url,title;
        final long time;
        Record(String url,String title,long time){
            this.url=url;this.title=title;this.time=time;
        }
    }
    private final SharedPreferences preferences;
    BrowserStore(Context context){
        preferences=context.getSharedPreferences(FILE,Context.MODE_PRIVATE);
    }
    private List<Record> read(String key){
        ArrayList<Record> result=new ArrayList<>();
        try{
            JSONArray records=new JSONArray(preferences.getString(key,"[]"));
            for(int i=0;i<records.length();i++){
                JSONObject obj=records.optJSONObject(i);
                if(obj==null)continue;
                String url=obj.optString("url","");
                if(!BrowserUrlRules.valid(url))continue;
                result.add(new Record(url,BrowserUrlRules.title(obj.optString("title"),url),
                                      obj.optLong("time",0)));
            }
        }catch(Exception ignored){}
        return result;
    }
    private void write(String key,List<Record> items){
        JSONArray array=new JSONArray();
        for(Record record:items){
            JSONObject obj=new JSONObject();
            try{
                obj.put("url",record.url);
                obj.put("title",record.title);
                obj.put("time",record.time);
                array.put(obj);
            }catch(Exception ignored){}
        }
        preferences.edit().putString(key,array.toString()).apply();
    }
    synchronized List<Record> bookmarks(){return read(BOOKMARKS);}
    synchronized List<Record> history(){return read(HISTORY);}
    synchronized boolean isBookmarked(String url){
        if(!BrowserUrlRules.valid(url))return false;
        for(Record b:read(BOOKMARKS))if(b.url.equals(url))return true;
        return false;
    }
    synchronized boolean addBookmark(String url,String title){
        if(!BrowserUrlRules.valid(url))return false;
        List<Record> records=read(BOOKMARKS);
        records.removeIf(item->item.url.equals(url));
        records.add(0,new Record(url,BrowserUrlRules.title(title,url),System.currentTimeMillis()));
        if(records.size()>MAX_BOOKMARKS)records=records.subList(0,MAX_BOOKMARKS);
        write(BOOKMARKS,records);
        return true;
    }
    synchronized void removeBookmark(String url){
        List<Record> records=read(BOOKMARKS);
        records.removeIf(item->item.url.equals(url));
        write(BOOKMARKS,records);
    }
    synchronized void recordVisit(String url,String title){
        if(!BrowserUrlRules.valid(url))return;
        List<Record> records=read(HISTORY);
        records.removeIf(item->item.url.equals(url));
        records.add(0,new Record(url,BrowserUrlRules.title(title,url),System.currentTimeMillis()));
        if(records.size()>MAX_HISTORY)records=records.subList(0,MAX_HISTORY);
        write(HISTORY,records);
    }
    synchronized void clearHistory(){preferences.edit().remove(HISTORY).apply();}
    synchronized String homepage(){
        String saved=preferences.getString(HOME,DEFAULT_HOME);
        return BrowserUrlRules.valid(saved)?saved:DEFAULT_HOME;
    }
    synchronized boolean setHomepage(String url){
        if(!BrowserUrlRules.valid(url))return false;
        preferences.edit().putString(HOME,url).apply();
        return true;
    }
}
