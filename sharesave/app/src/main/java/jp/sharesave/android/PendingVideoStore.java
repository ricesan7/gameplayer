package jp.sharesave.android;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** Keep successfully downloaded originals if publishing to MediaStore fails. */
final class PendingVideoStore {
    private PendingVideoStore(){}
    static File directory(Context context){
        return new File(context.getFilesDir(),"pending-videos");
    }
    static File newest(Context context){
        return CompletedVideoFile.choose(directory(context).listFiles());
    }
    static int count(Context context){
        File[] files=directory(context).listFiles();
        if(files==null)return 0;
        int n=0;
        for(File f:files)if(f.isFile()&&f.length()>0&&
            CompletedVideoFile.mime(f.getName())!=null)n++;
        return n;
    }
    static File hold(Context context,File completed) throws IOException{
        if(completed==null||!completed.isFile()||completed.length()==0)
            throw new IOException("復旧できる動画ファイルがありません");
        File dir=directory(context);
        if(!dir.exists()&&!dir.mkdirs())throw new IOException("保留フォルダを作成できません");
        String original=completed.getName().replaceAll("[^a-zA-Z0-9._-]","_");
        if(CompletedVideoFile.mime(original)==null)throw new IOException("未対応の動画形式");
        File target=new File(dir,System.currentTimeMillis()+"_"+original);
        // Cache and app-private files are on the same /data filesystem.
        Files.move(completed.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
    static long freeSpaceForMedia(Context context){
        File dir=context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES);
        return dir!=null?dir.getUsableSpace():-1;
    }
}
