package jp.sharesave.android;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.ffmpeg.FFmpeg;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;

public class MainActivity extends Activity {
    private static final long LIMIT=500L*1024*1024;
    private static final Pattern HTTPS=Pattern.compile("https://[^\\s<>\\\"']+",Pattern.CASE_INSENSITIVE);
    private final ExecutorService pool=Executors.newSingleThreadExecutor();
    private AtomicBoolean cancel=new AtomicBoolean(false);
    private volatile String processId;
    private EditText address;
    private TextView status;
    private ProgressBar progress;
    private WebView web;
    private Button stop;
    private int blue=Color.rgb(45,92,217);
    private boolean initialized=false;

    private LinearLayout.LayoutParams lp(int width,int height) {
        return new LinearLayout.LayoutParams(width,height);
    }
    private Button button(String label) {
        Button b=new Button(this);
        b.setText(label);
        b.setTextSize(13);
        b.setAllCaps(false);
        return b;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12,18,12,10);
        root.setBackgroundColor(Color.rgb(249,249,251));
        TextView title=new TextView(this);
        title.setText("ShareSave  動画・画像を保存");
        title.setTextSize(22);
        title.setTextColor(blue);
        title.setGravity(Gravity.CENTER);
        root.addView(title,lp(-1,58));
        address=new EditText(this);
        address.setTextSize(13);
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint("SNSの動画URL / https://...mp4");
        root.addView(address,lp(-1,52));
        LinearLayout toolbar=new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        Button open=button("ページを開く");
        Button save=button("動画を保存");
        open.setOnClickListener(v->openUrl(address.getText().toString()));
        save.setOnClickListener(v->saveLink(currentUrl()));
        toolbar.addView(open,new LinearLayout.LayoutParams(0,52,1));
        toolbar.addView(save,new LinearLayout.LayoutParams(0,52,1));
        root.addView(toolbar,lp(-1,56));
        status=new TextView(this);
        status.setText("SNSの共有メニューからも起動できます。");
        status.setTextColor(Color.DKGRAY);
        status.setTextSize(12);
        status.setGravity(Gravity.CENTER);
        root.addView(status,lp(-1,40));
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress,lp(-1,7));
        stop=button("ダウンロードを中止");
        stop.setVisibility(View.GONE);
        stop.setOnClickListener(v->{
            cancel.set(true);
            try { if(processId!=null) YoutubeDL.getInstance().destroyProcessById(processId); }
            catch(Exception ignored){}
            display("キャンセルを要求しました。");
        });
        root.addView(stop,lp(-1,43));
        web=new WebView(this);
        WebSettings s=web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView w,WebResourceRequest r) {
                if(!r.isForMainFrame()) return false;
                if(!allowed(r.getUrl().toString())) {
                    display("HTTPSの公開ページ以外は開けません。"); return true;
                }
                return false;
            }
            @Override public void onPageFinished(WebView w,String url) {
                if(allowed(url)) address.setText(url);
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.setDownloadListener((url,ua,disposition,mime,size)->{
            if(allowed(url)&&(directMedia(url)||mime!=null&&(mime.startsWith("video/")||mime.startsWith("image/"))))
                saveLink(url);
        });
        root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        receive(getIntent());
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        receive(intent);
    }
    @Override public void onBackPressed() {
        if(web.canGoBack())web.goBack();else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        cancel.set(true);
        try {if(processId!=null)YoutubeDL.getInstance().destroyProcessById(processId);}catch(Exception ignored){}
        pool.shutdownNow();
        web.stopLoading();
        web.destroy();
        super.onDestroy();
    }
    private void display(String text) {runOnUiThread(()->status.setText(text));}
    private String urlIn(String raw) {
        if(raw==null)return null;
        Matcher m=HTTPS.matcher(raw.trim());
        return m.find()?m.group().replaceAll("[),.;。]+$",""):null;
    }
    private String currentUrl() {
        String typed=urlIn(address.getText().toString());
        return typed!=null?typed:web.getUrl();
    }
    private void openUrl(String raw) {
        String url=urlIn(raw);
        if(!allowed(url)){display("公開HTTPSのURLを入力してください。");return;}
        address.setText(url);web.loadUrl(url);
        display("ページ表示と動画ファイルの保存は別の処理です。");
    }
    private static boolean allowed(String s) {
        try {
            if(s==null||s.length()>8192)return false;
            URI u=new URI(s);
            if(!"https".equalsIgnoreCase(u.getScheme())||u.getUserInfo()!=null)return false;
            String h=u.getHost();
            if(h==null)return false;
            h=h.toLowerCase(Locale.ROOT);
            if(h.equals("localhost")||h.endsWith(".local")||h.endsWith(".localhost")||
                h.endsWith(".internal")||h.endsWith(".test")||h.endsWith(".invalid"))return false;
            if(h.contains(":")||h.matches("[0-9.]+")) {
                InetAddress a=InetAddress.getByName(h);
                if(a.isAnyLocalAddress()||a.isLoopbackAddress()||a.isSiteLocalAddress()||
                   a.isLinkLocalAddress()||a.isMulticastAddress())return false;
            }
            return true;
        } catch(Exception e){return false;}
    }
    private static boolean directMedia(String raw) {
        try {
            String p=new URI(raw).getPath().toLowerCase(Locale.ROOT);
            for(String ext:new String[]{".mp4",".webm",".mov",".m4v",".jpg",".jpeg",".png",".webp",".gif",".heic"})
                if(p.endsWith(ext))return true;
        }catch(Exception ignored){}
        return false;
    }
    private void receive(Intent intent) {
        if(intent==null)return;
        String action=intent.getAction();
        if(Intent.ACTION_SEND.equals(action)||Intent.ACTION_SEND_MULTIPLE.equals(action)){
            CharSequence text=intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            String url=urlIn(text==null?null:text.toString());
            ArrayList<Uri> images=new ArrayList<>();
            if(Intent.ACTION_SEND.equals(action)){
                Uri first=(Uri)intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if(first!=null)images.add(first);
            }else{
                ArrayList<Uri> list=intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if(list!=null)images.addAll(list);
            }
            if(url!=null){address.setText(url);openUrl(url);}
            if(!images.isEmpty())saveStreams(images);
            else if(url!=null)saveLink(url);
        }
    }
    private void beginJob() {
        cancel.set(true);
        try{if(processId!=null)YoutubeDL.getInstance().destroyProcessById(processId);}catch(Exception ignored){}
        cancel=new AtomicBoolean(false);
        progress.setIndeterminate(true);
        progress.setVisibility(View.VISIBLE);
        stop.setVisibility(View.VISIBLE);
    }
    private void endJob() {
        runOnUiThread(()->{
            progress.setVisibility(View.GONE);
            stop.setVisibility(View.GONE);
        });
    }
    private void saveStreams(ArrayList<Uri> streams) {
        beginJob();AtomicBoolean stopFlag=cancel;
        pool.submit(()->{
            int count=0;
            try{
                for(Uri u:streams) {
                    if(stopFlag.get())throw new IOException("中止しました。");
                    String type=getContentResolver().getType(u);
                    if(type==null||( !type.startsWith("video/")&&!type.startsWith("image/")))
                        throw new IOException("画像か動画ファイルのみ保存できます。");
                    try(InputStream in=getContentResolver().openInputStream(u)){
                        if(in==null)throw new IOException("共有ファイルを開けません。");
                        store(in,type,"shared_"+System.currentTimeMillis(),stopFlag);
                        count++;
                    }
                }
                display(count+" 件をギャラリーに保存しました。");
            }catch(Exception e){display("保存失敗: "+e.getMessage());}
            finally{endJob();}
        });
    }
    private void saveLink(String raw) {
        String link=urlIn(raw);
        if(!allowed(link)){display("保存には公開HTTPSのURLが必要です。");return;}
        beginJob();AtomicBoolean stopFlag=cancel;
        pool.submit(()->{
            try {
                display("動画の取得を確認しています…");
                if(directMedia(link))downloadDirect(link,stopFlag);
                else downloadPost(link,stopFlag);
                if(!stopFlag.get())display("動画の保存が完了しました。ギャラリーをご確認ください。");
            }catch(Exception e) {
                display("保存できませんでした: "+e.getMessage());
            }finally{endJob();}
        });
    }
    private void downloadDirect(String link,AtomicBoolean stopFlag) throws Exception {
        URL url=new URL(link);
        for(int i=0;i<5;i++) {
            if(!allowed(url.toString()))throw new IOException("安全なURLではありません。");
            HttpURLConnection c=(HttpURLConnection)url.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent","ShareSave/0.3.1");
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
                if(size>LIMIT)throw new IOException("500MBの上限を超えています。");
                try(InputStream in=c.getInputStream()){
                    store(in,type,"video_"+System.currentTimeMillis(),stopFlag);
                }
                return;
            }finally{c.disconnect();}
        }
        throw new IOException("転送が多すぎます。");
    }
    private void downloadPost(String link,AtomicBoolean stopFlag) throws Exception {
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
            YoutubeDLRequest req=new YoutubeDLRequest(link);
            req.addOption("--ignore-config");
            req.addOption("--no-playlist");
            req.addOption("--no-write-thumbnail");
            req.addOption("--no-write-info-json");
            req.addOption("--socket-timeout",20);
            req.addOption("--retries",2);
            req.addOption("--max-filesize","500M");
            req.addOption("-f","bv*+ba/b");
            req.addOption("--merge-output-format","mp4");
            req.addOption("-o",new File(temp,"%(id)s.%(ext)s").getAbsolutePath());
            YoutubeDL.getInstance().execute(req,processId,(percent,eta,line)->{
                if(!stopFlag.get())runOnUiThread(()->{
                    progress.setIndeterminate(false);
                    progress.setProgress(Math.max(0,Math.min(100,(int)percent)));
                    status.setText("ダウンロード中 "+(int)percent+"%");
                });
                return kotlin.Unit.INSTANCE;
            });
            if(stopFlag.get())throw new IOException("中止しました。");
            File[] files=temp.listFiles(f->f.isFile()&&!f.getName().endsWith(".part")&&!f.getName().endsWith(".ytdl"));
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
                if(total>LIMIT)throw new IOException("500MBの上限を超えています。");
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
