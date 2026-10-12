package jp.sharesave.android;

import java.net.URI;
import java.util.Locale;

/** Matches manifest and media URLs without inspecting signed URL query parameters. */
final class StreamUrlMatcher {
    private StreamUrlMatcher() {}
    static String mediaKind(String url) {
        if(url==null || url.length()>8192)return null;
        try {
            URI u=new URI(url);
            if(!"https".equalsIgnoreCase(u.getScheme()))return null;
            String p=u.getPath();
            if(p==null)return null;
            p=p.toLowerCase(Locale.ROOT);
            if(p.endsWith(".m3u8"))return "HLS (m3u8)";
            if(p.endsWith(".mpd"))return "DASH (mpd)";
            if(p.endsWith(".mp4")||p.endsWith(".m4v"))return "MP4";
            if(p.endsWith(".webm"))return "WebM";
        }catch(Exception ignored){}
        return null;
    }
}
