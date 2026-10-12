package jp.sharesave.android;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Copies only cookies already held by WebView into an exact-host temporary jar.
 * No cross-domain Cookie header is added to unrelated CDN or redirected requests.
 */
final class SessionCookies {
    private SessionCookies(){}
    private static String host(String url){
        try {
            if(url==null)return null;
            URI u=new URI(url);
            if(!"https".equalsIgnoreCase(u.getScheme())||u.getUserInfo()!=null)return null;
            String h=u.getHost();
            if(h==null||h.indexOf(':')>=0)return null;
            h=h.toLowerCase(Locale.ROOT);
            if(h.equals("localhost")||h.matches("[0-9.]+"))return null;
            return h;
        }catch(Exception ignored){return null;}
    }
    private static void add(LinkedHashMap<String,String> entries,String url,String raw){
        String hostname=host(url);
        if(hostname==null||raw==null||raw.isEmpty())return;
        for(String chunk:raw.split(";")){
            String part=chunk.trim();
            int eq=part.indexOf('=');
            if(eq<=0)continue;
            String name=part.substring(0,eq).trim();
            String value=part.substring(eq+1).trim();
            if(!name.matches("[A-Za-z0-9_!#$%&'*+.^`|~-]{1,128}")||value.contains("\n")||value.contains("\r")||value.contains("\t"))continue;
            if(value.length()>4096)continue;
            String key=hostname+"\t"+name;
            entries.put(key,hostname+"\tFALSE\t/\tTRUE\t0\t"+name+"\t"+value+"\n");
        }
    }
    static int writeCookieJar(File destination,String mediaUrl,String mediaCookies,
                              String refererUrl,String refererCookies) throws Exception{
        LinkedHashMap<String,String> entries=new LinkedHashMap<>();
        add(entries,mediaUrl,mediaCookies);
        add(entries,refererUrl,refererCookies);
        try(Writer writer=new OutputStreamWriter(new FileOutputStream(destination),StandardCharsets.UTF_8)){
            writer.write("# Netscape HTTP Cookie File\n");
            for(String line:entries.values())writer.write(line);
        }
        return entries.size();
    }
}
