package jp.sharesave.android;

import java.io.File;
import java.util.Locale;

/** Select only a playable final video, not yt-dlp's fragment or audio sidecars. */
final class CompletedVideoFile {
    private CompletedVideoFile(){}
    static String mime(String name){
        if(name==null)return null;
        String n=name.toLowerCase(Locale.ROOT);
        if(n.endsWith(".mp4")||n.endsWith(".m4v"))return "video/mp4";
        if(n.endsWith(".webm"))return "video/webm";
        if(n.endsWith(".mkv"))return "video/x-matroska";
        if(n.endsWith(".mov"))return "video/quicktime";
        if(n.endsWith(".ts"))return "video/mp2t";
        return null;
    }
    private static boolean isCandidate(File f){
        if(f==null||!f.isFile()||f.length()<=0)return false;
        String n=f.getName().toLowerCase(Locale.ROOT);
        if(n.endsWith(".part")||n.endsWith(".ytdl")||
            n.endsWith(".temp")||n.endsWith(".tmp"))return false;
        return mime(n)!=null;
    }
    static File choose(File[] files){
        if(files==null)return null;
        File best=null;
        for(File f:files){
            if(!isCandidate(f))continue;
            if(best==null||f.length()>best.length())best=f;
        }
        return best;
    }
}
