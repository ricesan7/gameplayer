package jp.sharesave.android;

import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A small read-only HTTP probe before starting yt-dlp.
 *
 * Probes only a video manifest chosen by the user, never fragments or protected media.
 * Does not follow insecure redirects and never copies Cookie to a different host.
 */
final class StreamPreflight {
    static final class Result {
        final int status;
        final String kind;
        final String host;
        final String message;
        Result(int status,String kind,String host,String message){
            this.status=status;this.kind=kind;this.host=host;this.message=message;
        }
    }
    private StreamPreflight(){}
    static boolean isManifest(String url){
        String kind=StreamUrlMatcher.mediaKind(url);
        return kind!=null&&(kind.startsWith("HLS")||kind.startsWith("DASH"));
    }
    static Result check(String original,String referer,String agent,String origin,String cookies)
            throws IOException {
        if(!isManifest(original))return new Result(0,"ページURL","","配信情報の事前確認対象外");
        URL target=new URL(original);
        String initialHost=target.getHost();
        for(int redirect=0;redirect<=3;redirect++){
            if(!StreamDetails.safe(target.toString()))
                throw new IOException("安全でない配信先へ転送されたため停止しました");
            HttpURLConnection c=(HttpURLConnection)target.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setUseCaches(false);
            c.setRequestProperty("Accept-Encoding","identity");
            c.setRequestProperty("Accept","application/vnd.apple.mpegurl,application/x-mpegURL,application/dash+xml,*/*");
            if(agent!=null&&!agent.isEmpty())c.setRequestProperty("User-Agent",agent);
            if(StreamDetails.safe(referer))c.setRequestProperty("Referer",referer);
            if(origin!=null&&StreamDetails.safe(origin))c.setRequestProperty("Origin",origin);
            // Cookies are only ever sent to the exact host where WebView read them.
            if(cookies!=null&&!cookies.isEmpty()&&target.getHost().equalsIgnoreCase(initialHost))
                c.setRequestProperty("Cookie",cookies);
            try{
                int status=c.getResponseCode();
                if(status>=300&&status<400){
                    String location=c.getHeaderField("Location");
                    if(location==null)throw new IOException("配信先の転送URLがありません (HTTP "+status+")");
                    URL next=new URL(target,location);
                    if(!StreamDetails.safe(next.toString()))
                        throw new IOException("安全ではない転送先です");
                    target=next;
                    continue;
                }
                if(status!=200 && status!=206)
                    throw new IOException("HTTP "+status+" （配信情報を取得できません）");
                String contentType=c.getContentType();
                byte[] prefix=new byte[512];
                int read;
                try(InputStream input=c.getInputStream()){
                    read=input.read(prefix);
                }
                String body=read>0?new String(prefix,0,read,StandardCharsets.UTF_8):"";
                String normalized=body.trim().toLowerCase(Locale.ROOT);
                String kind=StreamUrlMatcher.mediaKind(original);
                boolean valid=kind.startsWith("HLS")?body.trim().startsWith("#EXTM3U"):
                    normalized.contains("<mpd")||normalized.contains("<mpd ");
                if(!valid){
                    String detail=contentType!=null?contentType:"Content-Type不明";
                    throw new IOException("HTTP "+status+" ですが配信情報ではない応答です ("+detail+")");
                }
                return new Result(status,kind,target.getHost(),
                    "HTTP "+status+" / "+kind+" の配信情報に接続できました");
            }finally{c.disconnect();}
        }
        throw new IOException("配信情報の転送が多すぎます");
    }
}
