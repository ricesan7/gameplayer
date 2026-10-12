package jp.sharesave.android;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded, best-effort pre-download size detection. Never downloads video segments. */
final class StreamDetails {
    static final class SizeInfo {
        final long bytes;
        final boolean estimated;
        SizeInfo(long bytes, boolean estimated){this.bytes=bytes;this.estimated=estimated;}
        String label(){
            if(bytes<0)return "サイズ未取得";
            return (estimated?"推定 約":"容量 ")+formatBytes(bytes);
        }
    }

    static String formatBytes(long bytes){
        if(bytes<0)return "不明";
        if(bytes<1024)return bytes+" B";
        double kb=bytes/1024.0;
        if(kb<1024)return String.format(Locale.US,"%.1f KB",kb);
        double mb=kb/1024.0;
        if(mb<1024)return String.format(Locale.US,"%.1f MB",mb);
        return String.format(Locale.US,"%.2f GB",mb/1024.0);
    }

    private static final Pattern BANDWIDTH=Pattern.compile("(?<![a-zA-Z-])BANDWIDTH=[\\\"']?(\\d+)",Pattern.CASE_INSENSITIVE);
    private static final Pattern EXTINF=Pattern.compile("(?m)^#EXTINF:([0-9]+(?:\\.[0-9]+)?)");
    static SizeInfo estimateManifest(String type,String text,double durationSeconds){
        if(text==null)return new SizeInfo(-1,true);
        long bitrate=0;
        Matcher bm=BANDWIDTH.matcher(text);
        while(bm.find()){
            try{bitrate=Math.max(bitrate,Long.parseLong(bm.group(1)));}catch(Exception ignored){}
        }
        if((!Double.isFinite(durationSeconds)||durationSeconds<=0)&&type.startsWith("HLS")){
            Matcher dm=EXTINF.matcher(text);
            double total=0;
            while(dm.find()){
                try{total+=Double.parseDouble(dm.group(1));}catch(Exception ignored){}
            }
            // HLS media playlists can have duration but usually not bandwidth.
            if(total>0)durationSeconds=total;
        }
        // DASH can state the presentation duration; keep duration unknown otherwise.
        if((!Double.isFinite(durationSeconds)||durationSeconds<=0)&&type.startsWith("DASH")){
            Matcher m=Pattern.compile("mediaPresentationDuration=[\\\"']PT(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?",Pattern.CASE_INSENSITIVE).matcher(text);
            if(m.find()){
                double h=m.group(1)==null?0:Double.parseDouble(m.group(1));
                double min=m.group(2)==null?0:Double.parseDouble(m.group(2));
                double sec=m.group(3)==null?0:Double.parseDouble(m.group(3));
                durationSeconds=h*3600+min*60+sec;
            }
        }
        if(bitrate<=0||!Double.isFinite(durationSeconds)||durationSeconds<=0||durationSeconds>172800)return new SizeInfo(-1,true);
        double estimated=(bitrate/8.0)*durationSeconds;
        if(!Double.isFinite(estimated)||estimated<1||estimated>Long.MAX_VALUE)return new SizeInfo(-1,true);
        return new SizeInfo(Math.round(estimated),true);
    }

    static SizeInfo inspect(String raw,String kind,String referer,String agent,double duration){
        try{
            if(kind.equals("MP4")||kind.equals("WebM")){
                long bytes=probeLength(raw,referer,agent);
                return new SizeInfo(bytes,false);
            }
            if(kind.startsWith("HLS")||kind.startsWith("DASH")){
                String manifest=readManifest(raw,referer,agent);
                return estimateManifest(kind,manifest,duration);
            }
        }catch(Exception ignored){}
        return new SizeInfo(-1,false);
    }
    static boolean safe(String raw){
        try{
            URI uri=new URI(raw);
            String host=uri.getHost();
            if(!"https".equalsIgnoreCase(uri.getScheme())||host==null||uri.getUserInfo()!=null)return false;
            host=host.toLowerCase(Locale.ROOT);
            return !(host.equals("localhost")||host.endsWith(".local")||host.endsWith(".internal")
                ||host.endsWith(".localhost")||host.endsWith(".test")||host.endsWith(".invalid")
                ||host.matches("[0-9.]+")||host.contains(":"));
        }catch(Exception e){return false;}
    }
    private static HttpURLConnection connect(String input,String method,String referer,String agent) throws Exception{
        return connect(input,method,referer,agent,false);
    }
    private static HttpURLConnection connect(String input,String method,String referer,String agent,boolean byteRange) throws Exception{
        URL current=new URL(input);
        for(int i=0;i<4;i++){
            if(!safe(current.toString()))throw new java.io.IOException("Invalid redirect");
            HttpURLConnection c=(HttpURLConnection)current.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod(method);
            c.setConnectTimeout(4500);
            c.setReadTimeout(4500);
            c.setRequestProperty("Accept-Encoding","identity");
            if(byteRange)c.setRequestProperty("Range","bytes=0-0");
            if(agent!=null&&!agent.isEmpty())c.setRequestProperty("User-Agent",agent);
            if(safe(referer))c.setRequestProperty("Referer",referer);
            int code=c.getResponseCode();
            if(code>=300&&code<400){
                String loc=c.getHeaderField("Location");
                c.disconnect();
                if(loc==null)throw new java.io.IOException("Redirect without location");
                current=new URL(current,loc);
                continue;
            }
            if(code!=200 && code!=206){
                c.disconnect();
                throw new java.io.IOException("HTTP "+code);
            }
            return c;
        }
        throw new java.io.IOException("Too many redirects");
    }
    private static long probeLength(String raw,String referer,String agent){
        try{
            HttpURLConnection c=connect(raw,"HEAD",referer,agent);
            try{
                long bytes=c.getContentLengthLong();
                if(bytes>0)return bytes;
            }finally{c.disconnect();}
        }catch(Exception ignored){}
        try{
            // Prefer a single-byte range request if HEAD is not supported.
            HttpURLConnection c=connect(raw,"GET",referer,agent,true);
            try{
                int code=c.getResponseCode();
                if(code==206){
                    String header=c.getHeaderField("Content-Range");
                    if(header==null)return -1L;
                    Matcher m=Pattern.compile("/(\\d+)$").matcher(header.trim());
                    if(m.find())return Long.parseLong(m.group(1));
                    return -1L;
                }
                // Some servers ignore Range; inspect headers without reading the body.
                long length=c.getContentLengthLong();
                return code==200&&length>0?length:-1L;
            }finally{c.disconnect();}
        }catch(Exception ignored){}
        return -1;
    }
    private static String readManifest(String raw,String referer,String agent) throws Exception{
        HttpURLConnection c=connect(raw,"GET",referer,agent);
        try{
            long len=c.getContentLengthLong();
            if(len>256*1024)return null;
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[4096];int total=0,n;
                while((n=in.read(buf))!=-1){
                    total+=n;if(total>256*1024)return null;
                    out.write(buf,0,n);
                }
                return new String(out.toByteArray(),StandardCharsets.UTF_8);
            }
        }finally{c.disconnect();}
    }
}
