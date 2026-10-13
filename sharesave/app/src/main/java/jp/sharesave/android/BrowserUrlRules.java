package jp.sharesave.android;

import java.net.URI;
import java.util.Locale;

/** Validation and labeling of local bookmark/history URLs. */
final class BrowserUrlRules {
    private BrowserUrlRules(){}
    static boolean valid(String url){
        try{
            if(url==null||url.isEmpty()||url.length()>8192)return false;
            URI u=new URI(url);
            if(!"https".equalsIgnoreCase(u.getScheme())||u.getUserInfo()!=null)return false;
            String host=u.getHost();
            if(host==null||host.length()>253)return false;
            host=host.toLowerCase(Locale.ROOT);
            if(host.equals("localhost")||host.endsWith(".localhost")||
               host.endsWith(".local")||host.endsWith(".internal")||
               host.endsWith(".invalid")||host.endsWith(".test"))return false;
            return true;
        }catch(Exception ignored){return false;}
    }
    static String title(String title,String url){
        String result=title==null?"":title.trim().replaceAll("[\\r\\n\\t]"," ");
        if(result.length()>100)result=result.substring(0,100);
        if(!result.isEmpty())return result;
        if(valid(url)){
            try{return new URI(url).getHost();}catch(Exception ignored){}
        }
        return "無題のページ";
    }
}
