package jp.sharesave.android;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.MediaStore;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.ffmpeg.FFmpeg;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DownloadService extends Service {
    public static final String ACTION_DOWNLOAD="jp.sharesave.android.DOWNLOAD";
    public static final String ACTION_SHARE="jp.sharesave.android.SHARE_MEDIA";
    public static final String ACTION_CANCEL="jp.sharesave.android.CANCEL";
    public static final String EXTRA_URL="url",EXTRA_REFERER="referer",EXTRA_UA="agent",
            EXTRA_ORIGIN="origin",EXTRA_MEDIA_COOKIE="mediaCookie",EXTRA_REFERER_COOKIE="refererCookie";
    public static final String EXTRA_URIS="uris";
    public static final String STATE_PREF="download_state";
    private static final String CHANNEL="sharesave_downloads";
    private static final int NOTIFICATION_ID=303;
    private static final long LIMIT=4L*1024*1024*1024;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy=new AtomicBoolean(false);
    private final AtomicBoolean cancel=new AtomicBoolean(false);
    private volatile String processId;
    private volatile boolean initialized;
    private volatile String lastNotification="";
    private volatile long lastNotifyMs=0;
    private PowerManager.WakeLock wakeLock;
    private NotificationManager manager;

    @Override public void onCreate(){
        super.onCreate();
        manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel=new NotificationChannel(CHANNEL,"ShareSave ダウンロード",NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("バックグラウンドのダウンロード状況");
        manager.createNotificationChannel(channel);
    }
    @Override public IBinder onBind(Intent i){return null;}
    public static SharedPreferences state(Context c){return c.getSharedPreferences(STATE_PREF,Context.MODE_PRIVATE);}
    public static void cancelFromActivity(Context c){
        Intent i=new Intent(c,DownloadService.class).setAction(ACTION_CANCEL);
        c.startService(i);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?null:intent.getAction();
        if(ACTION_CANCEL.equals(action)){
            cancel.set(true);
            String id=processId;
            if(id!=null)try{YoutubeDL.getInstance().destroyProcessById(id);}catch(Exception ignored){}
            publish("ダウンロードを中止しています…",-1,true);
            return START_NOT_STICKY;
        }
        if(!ACTION_DOWNLOAD.equals(action)&&!ACTION_SHARE.equals(action)){
            stopSelf();return START_NOT_STICKY;
        }
        if(!busy.compareAndSet(false,true)){
            publish("別のダウンロードが進行中です",-1,true);
            return START_NOT_STICKY;
        }
        cancel.set(false);
        String label="ダウンロード準備中";
        publish(label,-1,true);
        try{
            if(Build.VERSION.SDK_INT>=29)
                startForeground(NOTIFICATION_ID,notification(label,-1,true),
                   ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTIFICATION_ID,notification(label,-1,true));
        }catch(Exception e){
            busy.set(false);
            state(this).edit().putBoolean("active",false).putString("message","バックグラウンドサービスを開始できません: "+e.getMessage()).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        final Intent request=new Intent(intent);
        executor.submit(()->executeRequest(request));
        return START_NOT_STICKY;
    }
    private Notification notification(String description,int percent,boolean active){
        Intent home=new Intent(this,MainActivity.class);
        home.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open=PendingIntent.getActivity(this,11,home,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(active?"ShareSave 動画を保存中":"ShareSave")
            .setContentText(description)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(active)
            .setAutoCancel(!active)
            .setVisibility(Notification.VISIBILITY_PRIVATE);
        if(active){
            b.setProgress(100,Math.max(0,percent),percent<0);
            Intent stop=new Intent(this,DownloadService.class).setAction(ACTION_CANCEL);
            PendingIntent pi=PendingIntent.getService(this,12,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel,"中止",pi);
        }
        return b.build();
    }
    private void publish(String message,int percent,boolean active){
        state(this).edit().putString("message",message).putInt("percent",percent)
            .putBoolean("active",active).apply();
        long now=android.os.SystemClock.elapsedRealtime();
        // Reduce notification churn while retaining near-real-time progress.
        if(active && now-lastNotifyMs<700 && message.equals(lastNotification))return;
        if(active && now-lastNotifyMs<900 && percent>=0)return;
        lastNotification=message;lastNotifyMs=now;
        if(manager!=null)manager.notify(NOTIFICATION_ID,notification(message,percent,active));
    }
    private void executeRequest(Intent request){
        try{
            PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
            wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ShareSave:Downloading");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(6*60*60*1000L);
            if(ACTION_SHARE.equals(request.getAction()))saveStreams(request);
            else{
                String url=request.getStringExtra(EXTRA_URL);
                if(!StreamDetails.safe(url))throw new IOException("HTTPS動画URLが必要です");
                publish("動画の取得方法を確認しています",-1,true);
                String referer=request.getStringExtra(EXTRA_REFERER);
                if(directMedia(url) && referer==null) downloadDirect(url,cancel);
                else downloadPost(url,cancel,referer,request.getStringExtra(EXTRA_UA),
                        request.getStringExtra(EXTRA_ORIGIN),request.getStringExtra(EXTRA_MEDIA_COOKIE),
                        request.getStringExtra(EXTRA_REFERER_COOKIE));
            }
            if(cancel.get())throw new InterruptedIOException("ユーザーが中止しました");
            finish("ダウンロードが完了しました",false);
        }catch(Exception e){
            finish(cancel.get()?"ダウンロードを中止しました":"保存失敗: "+shortError(e),cancel.get());
        }finally{
            String id=processId;processId=null;
            if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();
            busy.set(false);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }
    private String shortError(Exception e){
        String message=e.getMessage();
        return message==null?e.getClass().getSimpleName():message.substring(0,Math.min(110,message.length()));
    }
    private void finish(String message,boolean cancelled){
        publish(message,100,false);
        Notification.Builder n=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("ShareSave").setContentText(message)
            .setAutoCancel(true);
        PendingIntent open=PendingIntent.getActivity(this,13,
            new Intent(this,SavedVideosActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        n.setContentIntent(open);
        manager.notify(NOTIFICATION_ID+1,n.build());
    }
    @Override public void onTimeout(int startId,int fgsType){
        cancel.set(true);
        String id=processId;
        if(id!=null)try{YoutubeDL.getInstance().destroyProcessById(id);}catch(Exception ignored){}
        state(this).edit().putBoolean("active",false).putString("message","Androidのバックグラウンド実行時間制限に達しました").apply();
        stopSelf();
    }
    @Override public void onDestroy(){
        cancel.set(true);
        String id=processId;
        if(id!=null)try{YoutubeDL.getInstance().destroyProcessById(id);}catch(Exception ignored){}
        if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();
        executor.shutdownNow();
        super.onDestroy();
    }
    private boolean directMedia(String url){
        try{
            String path=new URI(url).getPath().toLowerCase(Locale.ROOT);
            return path.endsWith(".mp4")||path.endsWith(".webm")||path.endsWith(".m4v")||
                   path.endsWith(".mov")||path.endsWith(".jpg")||path.endsWith(".jpeg")||
                   path.endsWith(".png")||path.endsWith(".webp");
        }catch(Exception ignored){return false;}
    }
    private void saveStreams(Intent intent) throws Exception{
        ArrayList<Uri> uris=intent.getParcelableArrayListExtra(EXTRA_URIS);
        if(uris==null||uris.isEmpty())throw new IOException("共有ファイルがありません");
        int saved=0;
        for(Uri uri:uris){
            if(cancel.get())throw new InterruptedIOException("中止しました");
            String mime=getContentResolver().getType(uri);
            if(mime==null||(!mime.startsWith("video/")&&!mime.startsWith("image/")))
                throw new IOException("動画または画像のみ保存できます");
            publish("共有ファイルを保存中 "+(saved+1)+"/"+uris.size(),-1,true);
            try(InputStream in=getContentResolver().openInputStream(uri)){
                if(in==null)throw new IOException("ファイルが読めません");
                store(in,mime,"shared_"+System.currentTimeMillis()+"_"+saved,cancel);
            }
            saved++;
        }
    }
    private void downloadDirect(String link,AtomicBoolean stopFlag) throws Exception {
        URL url=new URL(link);
        for(int i=0;i<5;i++) {
            if(!StreamDetails.safe(url.toString()))throw new IOException("安全なURLではありません。");
            HttpURLConnection c=(HttpURLConnection)url.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent","ShareSave/0.8.0");
            try {
                int code=c.getResponseCode();
                if(code>=300&&code<400) {
                    String location=c.getHeaderField("Location");
                    if(location==null)throw new IOException("転送先が不明です。");
                    url=new URL(url,location);
                    continue;
                }
                if(code!=200)throw new IOException("HTTP "+code);
                String type=c.getContentType();
                if(type!=null)type=type.split(";")[0].trim().toLowerCase(Locale.ROOT);
                if(type==null||(!type.startsWith("video/")&&!type.startsWith("image/")))
                    throw new IOException("URLは動画/画像データを返しません。");
                long size=c.getContentLengthLong();
                if(size>LIMIT)throw new IOException("4GBの上限を超えています。");
                try(InputStream in=c.getInputStream()){
                    store(in,type,"video_"+System.currentTimeMillis(),stopFlag);
                }
                return;
            }finally{c.disconnect();}
        }
        throw new IOException("転送が多すぎます。");
    }
    private void downloadPost(String link,AtomicBoolean stopFlag,String referer,String userAgent,
                              String origin,String mediaCookies,String refererCookies) throws Exception {
        if(!initialized){
            synchronized(this){
                if(!initialized){
                    YoutubeDL.getInstance().init(getApplicationContext());
                    FFmpeg.getInstance().init(getApplicationContext());
                    initialized=true;
                }
            }
        }
        File temp=new File(getCacheDir(),"extract-"+UUID.randomUUID());
        if(!temp.mkdirs())throw new IOException("作業領域を作成できません。");
        processId="sharesave-"+UUID.randomUUID();
        try {
            File jar=new File(temp,"browser-session-cookies.txt");
            int cookieCount=SessionCookies.writeCookieJar(
                jar,link,mediaCookies,referer,refererCookies);
            YoutubeDLRequest req=new YoutubeDLRequest(link);
            if(cookieCount>0)req.addOption("--cookies",jar.getAbsolutePath());
            req.addOption("--ignore-config");
            req.addOption("--no-playlist");
            req.addOption("--no-write-thumbnail");
            req.addOption("--no-write-info-json");
            req.addOption("--socket-timeout",20);
            // HLS and DASH are fragmented streams, not browser-cache files.
            req.addOption("--concurrent-fragments",3);
            if(referer!=null)req.addOption("--referer",referer);
            if(origin!=null)req.addOption("--add-header","Origin:"+origin);
            if(userAgent!=null&&!userAgent.isEmpty())req.addOption("--user-agent",userAgent);
            req.addOption("--retries",2);
            req.addOption("--max-filesize","4096M");
            req.addOption("-f","bv*+ba/b");
            req.addOption("--merge-output-format","mp4");
            req.addOption("--restrict-filenames");
            req.addOption("-o",new File(temp,"%(id)s.%(ext)s").getAbsolutePath());
            YoutubeDL.getInstance().execute(req,processId,(percent,eta,line)->{
                if(!stopFlag.get()){
                    int current=Math.max(0,Math.min(100,Math.round(percent)));
                    publish("動画をダウンロード中 "+current+"%",current,true);
                }
                return kotlin.Unit.INSTANCE;
            });
            if(stopFlag.get())throw new IOException("中止しました。");
            File[] files=temp.listFiles(f->f.isFile()&&!f.getName().endsWith(".part")&&!f.getName().endsWith(".ytdl")&&!f.getName().equals("browser-session-cookies.txt"));
            if(files==null||files.length==0)throw new IOException("このページから動画ファイルを取得できません。");
            if(files.length>20)throw new IOException("ファイルが多すぎます。");
            for(File f:files) {
                if(stopFlag.get())throw new IOException("中止しました。");
                if(f.length()>LIMIT||f.length()==0)throw new IOException("ファイルサイズ上限を超えています。");
                String type=f.getName().endsWith(".webm")?"video/webm":
                    f.getName().endsWith(".m4a")?"audio/mp4":"video/mp4";
                try(InputStream in=new FileInputStream(f)){
                    store(in,type,f.getName(),stopFlag);
                }
            }
        }finally {
            processId=null;
            File[] files=temp.listFiles();
            if(files!=null)for(File f:files)f.delete();
            temp.delete();
        }
    }
    private void store(InputStream in,String type,String filename,AtomicBoolean stopFlag) throws Exception {
        boolean image=type.startsWith("image/");
        String ext=image?(type.contains("png")?".png":type.contains("webp")?".webp":".jpg"):
            type.contains("webm")?".webm":type.contains("mp4")?".mp4":".mp4";
        String name=filename.matches(".*\\.[a-zA-Z0-9]{2,5}$")?filename:filename+ext;
        name=name.replaceAll("[^a-zA-Z0-9._-]","_");
        ContentValues values=new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
        values.put(MediaStore.MediaColumns.MIME_TYPE,type);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                (image?Environment.DIRECTORY_PICTURES:Environment.DIRECTORY_MOVIES)+"/ShareSave");
        values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri collection=image?MediaStore.Images.Media.EXTERNAL_CONTENT_URI:MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        Uri uri=getContentResolver().insert(collection,values);
        if(uri==null)throw new IOException("保存先を作成できませんでした。");
        boolean okay=false;
        try(OutputStream out=getContentResolver().openOutputStream(uri)){
            if(out==null)throw new IOException("保存先を開けませんでした。");
            byte[] buf=new byte[65536];long total=0;int n;
            while((n=in.read(buf))!=-1){
                if(stopFlag.get())throw new IOException("中止しました。");
                total+=n;
                if(total>LIMIT)throw new IOException("4GBの上限を超えています。");
                out.write(buf,0,n);
            }
            if(total==0)throw new IOException("動画が空でした。");
            okay=true;
        }finally{
            if(okay){
                ContentValues finish=new ContentValues();
                finish.put(MediaStore.MediaColumns.IS_PENDING,0);
                getContentResolver().update(uri,finish,null,null);
            }else getContentResolver().delete(uri,null,null);
        }
    }
}
