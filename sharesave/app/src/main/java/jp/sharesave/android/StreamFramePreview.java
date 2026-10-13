package jp.sharesave.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.yausername.ffmpeg.FFmpeg;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Explicit, low-bandwidth FFmpeg still-frame inspection. Called only after
 * tapping "3コマプレビュー"; never scans 15 HLS playlists automatically.
 * No cookies are passed to FFmpeg because redirects/segment hosts could leak
 * them. Sites which require cookies can continue using the ordinary poster.
 */
final class StreamFramePreview {
    static final class Frame {
        final Bitmap bitmap;
        final long timeMs;
        Frame(Bitmap bitmap,long timeMs){this.bitmap=bitmap;this.timeMs=timeMs;}
    }
    private static final int MAX_THUMB_BYTES=2*1024*1024;
    private static final int TIMEOUT_SECONDS=14;
    private StreamFramePreview(){}

    static List<Frame> extract(Context context,String raw,String referer,
                               String userAgent,double duration,AtomicBoolean cancelled){
        List<Frame> result=new ArrayList<>();
        if(!StreamDetails.safe(raw)||cancelled.get())return result;
        File parent=new File(context.getCacheDir(),"stream-previews");
        if(!parent.exists()&&!parent.mkdirs())return result;
        File work=new File(parent,"frames-"+System.nanoTime());
        if(!work.mkdirs())return result;
        try{
            FFmpeg.getInstance().init(context.getApplicationContext());
            File binary=new File(context.getApplicationInfo().nativeLibraryDir,"libffmpeg.so");
            if(!binary.isFile())return result;
            long[] times=StreamCandidateDescription.sampleTimes(duration);
            for(int i=0;i<times.length&&!cancelled.get();i++){
                File still=new File(work,"thumb-"+i+".jpg");
                boolean success=runOne(context,binary,raw,referer,userAgent,
                    times[i],still,cancelled);
                // Try a near-start frame for short/seek-restricted HLS only once.
                if(!success&&i==0&&times[i]>2000&&!cancelled.get()){
                    still.delete();
                    success=runOne(context,binary,raw,referer,userAgent,1000L,still,cancelled);
                    if(success)times[i]=1000L;
                }
                if(!success)continue;
                if(still.length()<=0||still.length()>MAX_THUMB_BYTES)continue;
                BitmapFactory.Options options=new BitmapFactory.Options();
                options.inPreferredConfig=Bitmap.Config.RGB_565;
                Bitmap bitmap=BitmapFactory.decodeFile(still.getAbsolutePath(),options);
                if(bitmap!=null)result.add(new Frame(bitmap,times[i]));
            }
        }catch(Exception ignored){
            // URL may be signed, DRM-encrypted or unsupported: use the
            // page poster as fallback instead of interrupting downloads.
        }finally{
            File[] files=work.listFiles();
            if(files!=null)for(File file:files)file.delete();
            work.delete();
        }
        return result;
    }
    private static boolean runOne(Context context,File binary,String url,
                                  String referer,String agent,long positionMs,
                                  File output,AtomicBoolean cancelled){
        List<String> args=new ArrayList<>();
        args.add(binary.getAbsolutePath());
        args.add("-hide_banner");args.add("-loglevel");args.add("error");
        args.add("-nostdin");args.add("-y");
        args.add("-rw_timeout");args.add("8000000");
        args.add("-protocol_whitelist");args.add("file,http,https,tcp,tls,crypto");
        // Input-side headers and seek occur before -i.
        if(agent!=null&&!agent.isEmpty()){
            args.add("-user_agent");args.add(agent);
        }
        if(StreamDetails.safe(referer)){
            args.add("-referer");args.add(referer);
        }
        args.add("-ss");
        args.add(String.format(Locale.US,"%.3f",positionMs/1000.0));
        args.add("-i");args.add(url);
        args.add("-an");args.add("-sn");args.add("-frames:v");args.add("1");
        args.add("-vf");args.add("scale=320:-2");
        args.add("-q:v");args.add("6");
        args.add("-f");args.add("image2");
        args.add(output.getAbsolutePath());
        Process process=null;
        try{
            ProcessBuilder builder=new ProcessBuilder(args);
            File packages=new File(context.getNoBackupFilesDir(),"youtubedl-android/packages");
            String libraries=new File(packages,"ffmpeg/usr/lib").getAbsolutePath()+
                ":"+new File(packages,"python/usr/lib").getAbsolutePath()+
                ":"+new File(packages,"aria2c/usr/lib").getAbsolutePath();
            builder.environment().put("LD_LIBRARY_PATH",libraries);
            builder.redirectErrorStream(true);
            // Never retain URL or network auth in a persistent log file.
            builder.redirectOutput(new File("/dev/null"));
            process=builder.start();
            boolean exited=process.waitFor(TIMEOUT_SECONDS,TimeUnit.SECONDS);
            if(!exited||cancelled.get()){
                process.destroyForcibly();
                return false;
            }
            return process.exitValue()==0&&output.isFile();
        }catch(Exception ignored){return false;}
        finally{if(process!=null&&process.isAlive())process.destroyForcibly();}
    }
}
