package jp.sharesave.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;

import com.yausername.ffmpeg.FFmpeg;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Best-effort, user-initiated video-frame extraction. Restricted to public HTTPS
 * media candidates. Provides a sanitized error category so failures no longer
 * appear as an indefinitely spinning, unresponsive button.
 */
final class StreamFramePreview {
    static final class Frame {
        final Bitmap bitmap;
        final long timeMs;
        Frame(Bitmap bitmap,long timeMs){this.bitmap=bitmap;this.timeMs=timeMs;}
    }
    static final class Result {
        final List<Frame> frames=new ArrayList<>();
        String status="動画フレームを確認できませんでした";
        String method="FFmpeg";
    }
    interface Listener {
        void update(String status, Frame frame);
    }
    private static final int MAX_THUMB_BYTES=2*1024*1024;
    private static final int MAX_LOG_BYTES=12*1024;
    private static final int TIMEOUT_SECONDS=17;
    private StreamFramePreview(){}

    static Result extract(Context context,String raw,String referer,
                          String userAgent,double duration,AtomicBoolean cancelled,
                          Listener listener){
        Result result=new Result();
        if(cancelled.get()){result.status="操作が中止されました";return result;}
        if(!StreamDetails.safe(raw)){
            result.status="安全なHTTPS動画URLが見つかりません";return result;
        }
        File parent=new File(context.getCacheDir(),"stream-previews");
        if(!parent.exists()&&!parent.mkdirs()){
            result.status="一時ファイルを作成できません（空き容量を確認）";return result;
        }
        File work=new File(parent,"frames-"+System.nanoTime());
        if(!work.mkdirs()){result.status="プレビュー作業フォルダを作成できません";return result;}
        try{
            FFmpeg.getInstance().init(context.getApplicationContext());
            File binary=new File(context.getApplicationInfo().nativeLibraryDir,"libffmpeg.so");
            if(!binary.isFile()){
                result.status="FFmpeg実行ファイルが見つかりません";return result;
            }
            long[] times=StreamCandidateDescription.sampleTimes(duration);
            boolean direct=raw.toLowerCase(Locale.ROOT).matches("(?s).*\\.(mp4|m4v|webm)(\\?.*)?$");
            for(int i=0;i<times.length&&!cancelled.get();i++){
                long ms=times[i];
                if(listener!=null)listener.update("映像を解析中 "+(i+1)+"/3  •  "+StreamCandidateDescription.timeLabel(ms),null);
                File still=new File(work,"thumb-"+i+".jpg");
                String failure=runOne(context,binary,raw,referer,userAgent,ms,still,cancelled);
                // The input may be too short or have a non-seekable HLS playlist.
                // First frame has a near-start retry to confirm source accessibility.
                if(failure!=null&&i==0&&ms>1500&&!cancelled.get()){
                    still.delete();
                    failure=runOne(context,binary,raw,referer,userAgent,1000L,still,cancelled);
                    if(failure==null)ms=1000L;
                }
                Bitmap bitmap=failure==null?decodeThumbnail(still):null;
                if(bitmap==null&&direct&&!cancelled.get()){
                    if(listener!=null)listener.update("Android動画デコーダーで再試行中…",null);
                    bitmap=androidFrame(raw,referer,userAgent,ms);
                    if(bitmap!=null)result.method="Android動画デコーダー";
                }
                if(bitmap!=null){
                    Frame frame=new Frame(bitmap,ms);
                    result.frames.add(frame);
                    if(listener!=null)listener.update("フレーム取得 "+result.frames.size()+"枚 / 3枚",frame);
                }else if(failure!=null){
                    result.status=failure;
                    if(listener!=null)listener.update("フレーム "+(i+1)+"/3："+failure,null);
                    // Authentication / forbidden / deleted URL is not worth retrying twice.
                    if(failure.contains("403")||failure.contains("401")||
                        failure.contains("期限切れ")||failure.contains("認証"))break;
                }
            }
        }catch(Exception e){
            result.status="プレビューエンジンを起動できません（"+
                e.getClass().getSimpleName()+"）";
        }finally{
            File[] files=work.listFiles();
            if(files!=null)for(File file:files)file.delete();
            work.delete();
        }
        if(!result.frames.isEmpty()){
            result.status="動画から "+result.frames.size()+"枚取得しました";
        }
        return result;
    }

    private static Bitmap decodeThumbnail(File source){
        if(!source.isFile()||source.length()==0||source.length()>MAX_THUMB_BYTES)return null;
        BitmapFactory.Options options=new BitmapFactory.Options();
        options.inPreferredConfig=Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(source.getAbsolutePath(),options);
    }
    private static Bitmap androidFrame(String url,String referer,String agent,long timeMs){
        MediaMetadataRetriever retriever=new MediaMetadataRetriever();
        try{
            Map<String,String> headers=new HashMap<>();
            if(StreamDetails.safe(referer))headers.put("Referer",referer);
            if(agent!=null&&!agent.isEmpty())headers.put("User-Agent",agent);
            retriever.setDataSource(url,headers);
            Bitmap bitmap=retriever.getFrameAtTime(timeMs*1000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if(bitmap==null)return null;
            int width=bitmap.getWidth(),height=bitmap.getHeight();
            if(width<=0||height<=0)return bitmap;
            float scale=Math.min(1f,320f/width);
            if(scale==1f)return bitmap;
            Bitmap resized=Bitmap.createScaledBitmap(bitmap,320,
                Math.max(2,Math.round(height*scale)),true);
            if(resized!=bitmap)bitmap.recycle();
            return resized;
        }catch(Exception ignored){return null;}
        finally{try{retriever.release();}catch(Exception ignored){}}
    }
    /**
     * Returns null on success, otherwise a safe, actionable failure reason.
     * Never surfaces the signed input URL, Authorization or Cookie values.
     */
    private static String runOne(Context context,File binary,String url,
                                 String referer,String agent,long positionMs,
                                 File output,AtomicBoolean cancelled){
        List<String> args=new ArrayList<>();
        args.add(binary.getAbsolutePath());
        args.add("-hide_banner");args.add("-loglevel");args.add("error");
        args.add("-nostdin");args.add("-y");
        args.add("-rw_timeout");args.add("7000000");
        args.add("-protocol_whitelist");args.add("file,http,https,tcp,tls,crypto,data");
        if(agent!=null&&!agent.isEmpty()){
            args.add("-user_agent");args.add(agent);
        }
        if(StreamDetails.safe(referer)){
            args.add("-referer");args.add(referer);
            // Many HLS providers require the webpage origin in addition to Referer.
            try{
                URI ref=new URI(referer);
                if("https".equalsIgnoreCase(ref.getScheme())&&ref.getHost()!=null){
                    String origin="https://"+ref.getHost()+
                        (ref.getPort()>0?":"+ref.getPort():"");
                    args.add("-headers");
                    args.add("Origin: "+origin+"\r\nAccept-Encoding: identity\r\n");
                }
            }catch(Exception ignored){}
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
        File log=new File(output.getParentFile(),output.getName()+".stderr");
        try{
            ProcessBuilder builder=new ProcessBuilder(args);
            File packages=new File(context.getNoBackupFilesDir(),"youtubedl-android/packages");
            File pythonLib=new File(packages,"python/usr/lib");
            File ffmpegLib=new File(packages,"ffmpeg/usr/lib");
            File aria2Lib=new File(packages,"aria2c/usr/lib");
            builder.environment().put("LD_LIBRARY_PATH",
                pythonLib.getAbsolutePath()+":"+ffmpegLib.getAbsolutePath()+":"+aria2Lib.getAbsolutePath());
            File cert=new File(packages,"python/usr/etc/tls/cert.pem");
            if(cert.isFile())builder.environment().put("SSL_CERT_FILE",cert.getAbsolutePath());
            builder.environment().put("TMPDIR",context.getCacheDir().getAbsolutePath());
            builder.redirectErrorStream(true);
            builder.redirectOutput(log);
            process=builder.start();
            boolean exited=process.waitFor(TIMEOUT_SECONDS,TimeUnit.SECONDS);
            if(!exited||cancelled.get()){
                process.destroyForcibly();
                return cancelled.get()?"キャンセルしました":"映像取得が時間切れ（17秒）";
            }
            if(process.exitValue()==0&&output.isFile()&&output.length()>0)return null;
            return reason(readLog(log));
        }catch(Exception e){
            return "FFmpeg起動エラー（"+e.getClass().getSimpleName()+"）";
        }finally{
            if(process!=null&&process.isAlive())process.destroyForcibly();
            log.delete();
        }
    }
    private static String readLog(File file){
        if(!file.isFile())return "";
        try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[1024];int read,total=0;
            while((read=in.read(buf))!=-1){
                int kept=Math.min(read,MAX_LOG_BYTES-total);
                if(kept>0)out.write(buf,0,kept);
                total+=kept;if(total>=MAX_LOG_BYTES)break;
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }catch(Exception ignored){return "";}
    }
    static String reason(String log){
        if(log==null||log.isEmpty())return "映像をデコードできません（詳細なし）";
        String s=log.toLowerCase(Locale.ROOT);
        if(s.contains("403")||s.contains("forbidden"))return "HTTP 403：配信のアクセス権またはURL期限切れ";
        if(s.contains("401")||s.contains("unauthorized"))return "HTTP 401：配信に認証が必要です";
        if(s.contains("404")||s.contains("not found"))return "HTTP 404：配信URLが無効です";
        if(s.contains("429")||s.contains("too many requests"))return "HTTP 429：配信先のアクセス制限";
        if(s.contains("certificate")||s.contains("ssl")||s.contains("tls"))return "配信先のTLS証明書を確認できません";
        if(s.contains("protocol not found")||s.contains("protocol not on whitelist"))return "配信形式がFFmpegの通信プロトコルに非対応";
        if(s.contains("invalid data")||s.contains("error while decoding"))return "動画形式または暗号化方式に非対応";
        if(s.contains("could not find codec parameters")||s.contains("decoder"))return "映像コーデックを判別できません";
        if(s.contains("permission denied")||s.contains("cannot link executable"))return "FFmpeg実行権限または依存ライブラリに問題があります";
        if(s.contains("connection")||s.contains("timed out")||s.contains("network"))return "配信への接続に失敗しました";
        return "FFmpegで映像フレームを抽出できません（形式・認証を確認）";
    }
}
