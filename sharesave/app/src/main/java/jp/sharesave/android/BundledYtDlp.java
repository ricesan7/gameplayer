package jp.sharesave.android;

import android.content.Context;
import android.content.SharedPreferences;
import com.yausername.youtubedl_android.YoutubeDL;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ships a known-fixed official yt-dlp core in the APK, without requiring
 * runtime GitHub access before a download can begin.
 *
 * Downloaded by CI from the verified yt-dlp official release, SHA-256 pinned.
 * Install happens after YoutubeDL.init() and before calling execute().
 */
final class BundledYtDlp {
    static final String VERSION="2026.08.19";
    static final String ASSET="yt-dlp-core-2026.08.19";
    static final String SHA256="1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6";
    private static final String PREF="bundled_yt_dlp";
    private static final Pattern DATE=Pattern.compile("(\\d{4})\\.(\\d{2})\\.(\\d{2})");

    private BundledYtDlp(){}

    private static int compareVersions(String a,String b){
        Matcher left=DATE.matcher(a==null?"":a);
        Matcher right=DATE.matcher(b);
        if(!left.find()||!right.find())return -1;
        for(int i=1;i<=3;i++){
            int cmp=Integer.compare(Integer.parseInt(left.group(i)),Integer.parseInt(right.group(i)));
            if(cmp!=0)return cmp;
        }
        return 0;
    }
    static synchronized String ensure(Context context) throws Exception {
        SharedPreferences prefs=context.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        File dir=new File(context.getNoBackupFilesDir(),"youtubedl-android/yt-dlp");
        File target=new File(dir,"yt-dlp");
        String remembered=prefs.getString("pinned_version","");
        // Keep a newer installation made through the official library updater.
        String upstreamVersion=YoutubeDL.getInstance().version(context);
        if(target.isFile() && target.length()>0 &&
            compareVersions(upstreamVersion,VERSION)>=0){
            return upstreamVersion;
        }
        if(VERSION.equals(remembered) && target.isFile() && target.length()==3072469L){
            return VERSION;
        }
        if(!dir.isDirectory() && !dir.mkdirs())throw new IOException("Unable to create yt-dlp directory");

        File tmp=File.createTempFile("yt-dlp-upgrade-",".partial",dir);
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream input=context.getAssets().open(ASSET);
                FileOutputStream output=new FileOutputStream(tmp)){
                byte[] buffer=new byte[32768];
                int n;long total=0;
                while((n=input.read(buffer))!=-1){
                    total+=n;
                    if(total>4000000L)throw new IOException("Bundled core unexpectedly large");
                    digest.update(buffer,0,n);
                    output.write(buffer,0,n);
                }
                output.getFD().sync();
            }
            StringBuilder hash=new StringBuilder();
            for(byte b:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",b&0xff));
            if(!SHA256.equals(hash.toString()))
                throw new IOException("Bundled yt-dlp SHA-256 mismatch");

            // Same directory and filesystem: move into place after verifying bytes.
            Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);
            prefs.edit().putString("pinned_version",VERSION).apply();
            return VERSION;
        }finally{
            if(tmp.exists())tmp.delete();
        }
    }
}
