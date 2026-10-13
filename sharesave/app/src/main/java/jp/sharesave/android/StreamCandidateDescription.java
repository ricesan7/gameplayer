package jp.sharesave.android;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure, privacy-conscious labels for the selectable stream variants. */
final class StreamCandidateDescription {
    private static final Pattern SIZE=Pattern.compile("(?i)(?:^|[^0-9])(\\d{3,4})[xX](\\d{3,4})(?:[^0-9]|$)");
    private static final Pattern P=Pattern.compile("(?i)(?:^|[^0-9])(240|360|480|540|720|1080|1440|2160|4320)p(?:[^0-9]|$)");
    private StreamCandidateDescription(){}
    static String fingerprint(String url){
        if(url==null)return "----";
        // Stable short reference for visually distinguishing otherwise identical
        // candidates; never expose signed URL query parameters.
        return String.format(Locale.US,"%04X",url.hashCode()&0xffff);
    }
    static String quality(String url,int knownWidth,int knownHeight){
        if(knownHeight>0&&knownWidth>0)
            return knownWidth+"×"+knownHeight+"（プレイヤー検出）";
        try{
            String path=new URI(url).getPath();
            if(path==null)return "画質未取得";
            Matcher m=SIZE.matcher(path);
            if(m.find())return "推定 "+m.group(1)+"×"+m.group(2);
            m=P.matcher(path);
            if(m.find())return "推定 "+m.group(1)+"p";
            if(path.toLowerCase(Locale.ROOT).contains("master"))
                return "マスタープレイリスト・画質未取得";
        }catch(Exception ignored){}
        return "画質未取得";
    }
    static String source(String url){
        try{
            URI uri=new URI(url);
            String path=uri.getPath();
            String host=uri.getHost();
            if(host==null)return "取得元不明";
            if(path==null||path.isEmpty()||"/".equals(path))return host;
            String name=path.substring(path.lastIndexOf('/')+1);
            if(name.isEmpty())name="stream";
            if(name.length()>28)name=name.substring(0,25)+"…";
            return host+" / "+name;
        }catch(Exception ignored){return "取得元不明";}
    }
    static long[] sampleTimes(double duration){
        if(!Double.isFinite(duration)||duration<=0){
            return new long[]{10000L,35000L,70000L};
        }
        long[] targets={Math.round(duration*0.12*1000),
                        Math.round(duration*0.45*1000),
                        Math.round(duration*0.76*1000)};
        long max=Math.max(0L,Math.round(duration*1000)-400L);
        for(int i=0;i<targets.length;i++)
            targets[i]=Math.max(0L,Math.min(max,targets[i]));
        return targets;
    }
    static String timeLabel(long milliseconds){
        long sec=Math.max(0,milliseconds/1000);
        return String.format(Locale.US,"%02d:%02d",sec/60,sec%60);
    }
}
