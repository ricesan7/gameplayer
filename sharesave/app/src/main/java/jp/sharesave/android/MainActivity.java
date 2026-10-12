package jp.sharesave.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceResponse;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.ffmpeg.FFmpeg;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;

public class MainActivity extends Activity {
    private static final long LIMIT=4L*1024*1024*1024; // 4 GiB max for long HLS recordings
    private static final Pattern HTTPS=Pattern.compile("https://[^\\s<>\\\"']+",Pattern.CASE_INSENSITIVE);
    // Transfers are owned by DownloadService, not by the Activity lifecycle.
    private final Handler downloadUiHandler=new Handler(Looper.getMainLooper());
    private String lastTransferMessageShown="";
    private final Runnable updateDownloadUi=new Runnable(){
        @Override public void run(){
            SharedPreferences prefs=DownloadService.state(MainActivity.this);
            boolean active=DownloadService.isRunning();
            if(!active&&prefs.getBoolean("active",false))
                prefs.edit().putBoolean("active",false).apply();
            String message=prefs.getString("message","");
            int percent=prefs.getInt("percent",-1);
            if(active || (!message.isEmpty()&&!message.equals(lastTransferMessageShown))){
                lastTransferMessageShown=message;
                if(status!=null)status.setText(message);
                if(progress!=null){
                    progress.setVisibility(active?View.VISIBLE:View.GONE);
                    progress.setIndeterminate(percent<0);
                    if(percent>=0)progress.setProgress(percent);
                }
                if(stop!=null)stop.setVisibility(active?View.VISIBLE:View.GONE);
            }
            downloadUiHandler.postDelayed(this,850);
        }
    };
    private final ExecutorService previewPool=Executors.newFixedThreadPool(3);
    private AtomicBoolean cancel=new AtomicBoolean(false);
    private volatile String processId;
    private EditText address;
    private TextView status;
    private ProgressBar progress;
    private WebView web;
    private Button stop;
    private int blue=Color.rgb(45,92,217);
    private boolean initialized=false;
    // Lock the currently displayed video page to defeat unsolicited redirects.
    private boolean pageLockEnabled=true;
    private String pinnedPageUrl=null;
    private Button pageLockButton;
    private Button streamButton;
    private Button freshButton;
    private final Object streamsMutex=new Object();
    private final LinkedHashMap<String,StreamCandidate> detectedStreams=new LinkedHashMap<>();
    private static final int MAX_CANDIDATES=15;
    private static class StreamCandidate {
        final String url;
        final String kind;
        final String referer;
        final String origin;
        final long foundAtMs;
        StreamCandidate(String url,String kind,String referer,String origin){
            this.url=url;this.kind=kind;this.referer=referer;this.origin=origin;
            foundAtMs=android.os.SystemClock.elapsedRealtime();
        }
    }
    private static String requestHeader(Map<String,String> headers,String key){
        if(headers==null)return null;
        for(Map.Entry<String,String> item:headers.entrySet())
            if(key.equalsIgnoreCase(item.getKey()))return item.getValue();
        return null;
    }
    private static String validReferer(String referer){
        return allowed(referer)?referer:null;
    }
    private static String validOrigin(String origin){
        if(!allowed(origin))return null;
        try {
            URI u=new URI(origin);
            String path=u.getRawPath();
            if(u.getRawQuery()!=null||u.getRawFragment()!=null||
                (path!=null&&!path.isEmpty()&&!"/".equals(path)))return null;
            return origin;
        }catch(Exception ignored){return null;}
    }
    private void resetDetectedStreams(){
        synchronized(streamsMutex){detectedStreams.clear();}
        updateStreamButton();
    }
    private void updateStreamButton(){
        int n;
        synchronized(streamsMutex){n=detectedStreams.size();}
        runOnUiThread(()->{
            if(streamButton!=null)streamButton.setText("📺 再生中の配信を保存 ("+n+")");
        });
    }
    private void observeMediaRequest(String url){observeMediaRequest(url,null,null);}
    private void observeMediaRequest(String url,String referer,String origin){
        if(!allowed(url))return;
        String kind=StreamUrlMatcher.mediaKind(url);
        if(kind==null)return;
        referer=validReferer(referer);
        origin=validOrigin(origin);
        synchronized(streamsMutex){
            StreamCandidate previous=detectedStreams.get(url);
            if(previous!=null){
                if(previous.referer==null&&referer!=null)
                    detectedStreams.put(url,new StreamCandidate(url,kind,referer,origin));
                return;
            }
            if(detectedStreams.size()>=MAX_CANDIDATES){
                String oldest=detectedStreams.keySet().iterator().next();
                detectedStreams.remove(oldest);
            }
            detectedStreams.put(url,new StreamCandidate(url,kind,referer,origin));
        }
        updateStreamButton();
    }
    private String candidateLabel(StreamCandidate candidate){
        try{
            URI u=new URI(candidate.url);
            String host=u.getHost();
            return candidate.kind+"  •  "+(host==null?"不明":host);
        }catch(Exception e){return candidate.kind;}
    }
    // Extract metadata only from the displayed page, without intercepting video bytes.
    private static final String PAGE_PREVIEW_SCRIPT =
        "(function(){"+
        "var out=[],vids=document.querySelectorAll('video');"+
        "for(var i=0;i<Math.min(vids.length,20);i++){"+
        "var v=vids[i],srcs=[],poster=v.poster||'',title=v.getAttribute('title')||v.getAttribute('aria-label')||'';"+
        "if(v.currentSrc)srcs.push(v.currentSrc);if(v.src)srcs.push(v.src);"+
        "var parts=v.querySelectorAll('source');for(var j=0;j<parts.length;j++){if(parts[j].src)srcs.push(parts[j].src);}"+
        "out.push({urls:srcs,poster:poster,title:title,duration:(isFinite(v.duration)&&v.duration>0?v.duration:0)});"+
        "}"+
        "var meta=document.querySelector('meta[property=\\\"og:image\\\"],meta[name=\\\"twitter:image\\\"]');"+
        "var pic=meta?meta.content:'';try{if(pic)pic=new URL(pic,document.baseURI).href;}catch(e){pic='';}"+
        "return {videos:out,pagePoster:pic,title:(document.title||'').slice(0,120)};"+
        "})()";

    private void selectStreamToSave(){
        display("配信候補を読み込んでいます…");
        final AtomicBoolean callbackResolved=new AtomicBoolean(false);
        final Handler handler=new Handler(Looper.getMainLooper());
        final Runnable fallback=()->{
            if(!callbackResolved.compareAndSet(false,true))return;
            ArrayList<StreamCandidate> candidates;
            synchronized(streamsMutex){candidates=new ArrayList<>(detectedStreams.values());}
            if(!candidates.isEmpty()){
                renderStreamChoices(candidates,new ArrayList<>(),"","");
                display("動画情報の応答が遅いため、検出済みURLから候補一覧を表示しました。");
            }else display("動画情報の取得に時間がかかっています。再生後にもう一度お試しください。");
        };
        handler.postDelayed(fallback,6000);
        web.evaluateJavascript(PAGE_PREVIEW_SCRIPT,result->{
            if(!callbackResolved.compareAndSet(false,true))return;
            handler.removeCallbacks(fallback);
            final ArrayList<JSONObject> videoInfo=new ArrayList<>();
            String pageTitle="";
            String fallbackPoster="";
            try{
                JSONObject info=new JSONObject(result);
                pageTitle=info.optString("title","");
                fallbackPoster=info.optString("pagePoster","");
                JSONArray videos=info.optJSONArray("videos");
                if(videos!=null){
                    for(int i=0;i<videos.length();i++){
                        JSONObject item=videos.optJSONObject(i);
                        if(item==null)continue;
                        videoInfo.add(item);
                        JSONArray urls=item.optJSONArray("urls");
                        if(urls!=null)for(int j=0;j<urls.length();j++)
                            observeMediaRequest(urls.optString(j,""));
                    }
                }
            }catch(Exception ignored){}
            final ArrayList<StreamCandidate> candidates;
            synchronized(streamsMutex){candidates=new ArrayList<>(detectedStreams.values());}
            if(candidates.isEmpty()){
                new AlertDialog.Builder(this).setTitle("配信URLが見つかりません")
                    .setMessage("動画を一度再生してからお試しください。取得できない場合は「動画を保存」でページURLからの取得も試せます。")
                    .setPositiveButton("OK",null).show();
                return;
            }
            renderStreamChoices(candidates,videoInfo,pageTitle,fallbackPoster);
        });
    }

    private void renderStreamChoices(ArrayList<StreamCandidate> candidates,
                                     ArrayList<JSONObject> videos,String pageTitle,String fallbackPoster){
        final String page=web.getUrl();
        final String userAgent=web.getSettings().getUserAgentString();
        ScrollView scroller=new ScrollView(this);
        LinearLayout list=new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12),dp(8),dp(12),dp(10));
        scroller.addView(list);
        TextView note=new TextView(this);
        note.setText("画像・配信形式・容量を比較して選択できます。HLS/DASHの容量は概算の場合があります。");
        note.setTextSize(12);note.setTextColor(Color.DKGRAY);
        list.addView(note,lp(-1,-2));

        AlertDialog dialog=new AlertDialog.Builder(this)
            .setTitle("保存する動画を選択")
            .setView(scroller)
            .setNegativeButton("閉じる",null)
            .create();
        final ArrayList<Runnable> jobs=new ArrayList<>();

        for(int index=0;index<candidates.size();index++){
            StreamCandidate c=candidates.get(index);
            String poster=fallbackPoster;
            String title=pageTitle;
            double duration=0;
            boolean imageFromVideo=false;
            for(JSONObject video:videos){
                JSONArray arr=video.optJSONArray("urls");
                boolean matched=false;
                if(arr!=null)for(int k=0;k<arr.length();k++)
                    if(c.url.equals(arr.optString(k,"")))matched=true;
                if(matched){
                    String candidateTitle=video.optString("title","");
                    if(!candidateTitle.isEmpty())title=candidateTitle;
                    String specificPoster=video.optString("poster","");
                    if(allowed(specificPoster)){
                        poster=specificPoster;
                        imageFromVideo=true;
                    }
                    duration=video.optDouble("duration",0);
                    break;
                }
            }
            final String imageUrl=allowed(poster)?poster:null;
            final String safePage=allowed(page)?page:null;
            final double seconds=duration;
            final String friendlyTitle=title.isEmpty()?"動画候補 "+(index+1):title;
            LinearLayout row=new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(5),dp(12),dp(5),dp(12));

            ImageView thumbnail=new ImageView(this);
            thumbnail.setBackgroundColor(Color.rgb(229,233,238));
            thumbnail.setImageResource(android.R.drawable.ic_media_play);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            row.addView(thumbnail,lp(118,74));

            LinearLayout detail=new LinearLayout(this);
            detail.setPadding(dp(12),0,0,0);
            detail.setOrientation(LinearLayout.VERTICAL);
            TextView name=new TextView(this);
            name.setText(friendlyTitle);
            name.setTextSize(14);
            name.setTextColor(Color.BLACK);
            name.setMaxLines(2);
            detail.addView(name,lp(-1,-2));

            TextView kind=new TextView(this);
            kind.setText(candidateLabel(c));
            kind.setTextSize(11);
            kind.setTextColor(Color.DKGRAY);
            detail.addView(kind,lp(-1,-2));

            TextView size=new TextView(this);
            size.setText("容量を確認中...");
            size.setTextSize(12);
            size.setTextColor(blue);
            detail.addView(size,lp(-1,-2));
            TextView origin=new TextView(this);
            origin.setText(imageFromVideo?"画像: 動画から取得":"画像: ページ代表（未照合）");
            origin.setTextSize(10);
            origin.setTextColor(Color.DKGRAY);
            detail.addView(origin,lp(-1,-2));
            row.addView(detail,new LinearLayout.LayoutParams(0,-2,1));

            row.setClickable(true);
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            row.setOnClickListener(v->{
                long age=android.os.SystemClock.elapsedRealtime()-c.foundAtMs;
                if(age>90000){
                    new AlertDialog.Builder(this)
                        .setTitle("配信URLが古い可能性があります")
                        .setMessage("配信URLを検出してから90秒以上経過しています。動画を再生し直して最新の配信URLを取得できます。")
                        .setPositiveButton("再取得",(d,w)->{
                            dialog.dismiss();
                            reloadAndFindLatestStreams();
                        })
                        .setNegativeButton("そのまま試す",(d,w)->{
                            dialog.dismiss();
                            saveLinkWithContext(c.url,c.referer!=null?c.referer:safePage,c.origin);
                        }).show();
                }else{
                    dialog.dismiss();
                    saveLinkWithContext(c.url,c.referer!=null?c.referer:safePage,c.origin);
                }
            });
            list.addView(row,lp(-1,-2));
            View divider=new View(this);
            divider.setBackgroundColor(Color.rgb(222,226,230));
            list.addView(divider,lp(-1,1));

            final String caption=imageFromVideo?"動画サムネイル":"ページ代表画像（動画との一致未確認）";
            // Image and size probing are independent: show previews as soon as ready.
            jobs.add(()->{
                Bitmap bitmap=loadThumbnail(imageUrl,safePage,userAgent);
                runOnUiThread(()->{
                    if(!dialog.isShowing()||isFinishing()||isDestroyed())return;
                    if(bitmap!=null){
                        thumbnail.setImageBitmap(bitmap);
                        thumbnail.setContentDescription(caption);
                    }else{
                        thumbnail.setContentDescription("サムネイル未取得");
                    }
                });
            });
            // Avoid re-fetching every signed HLS/DASH playlist during preview:
            // rapid repeated requests may be rate limited by the CDN.
            if(c.kind.startsWith("HLS")||c.kind.startsWith("DASH")){
                size.setText("容量は保存後に確認（配信形式）");
            }else{
                jobs.add(()->{
                    StreamDetails.SizeInfo info=StreamDetails.inspect(c.url,c.kind,safePage,userAgent,seconds);
                    runOnUiThread(()->{
                        if(dialog.isShowing()&&!isFinishing()&&!isDestroyed())
                            size.setText(info.label());
                    });
                });
            }
        }
        dialog.show();
        // Launch asynchronous inspection only after the dialog can receive updates.
        for(Runnable job:jobs)previewPool.submit(job);
    }
    private Bitmap loadThumbnail(String raw,String referer,String agent){
        if(!StreamDetails.safe(raw))return null;
        try{
            URL url=new URL(raw);
            for(int redirect=0;redirect<4;redirect++){
                if(!StreamDetails.safe(url.toString()))return null;
                HttpURLConnection c=(HttpURLConnection)url.openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(4500);c.setReadTimeout(4500);
                if(agent!=null)c.setRequestProperty("User-Agent",agent);
                if(StreamDetails.safe(referer))c.setRequestProperty("Referer",referer);
                try{
                    int code=c.getResponseCode();
                    if(code>=300&&code<400){
                        String next=c.getHeaderField("Location");
                        if(next==null)return null;
                        url=new URL(url,next);continue;
                    }
                    if(code!=200||c.getContentLengthLong()>1024*1024)return null;
                    String type=c.getContentType();
                    if(type==null||!type.toLowerCase(Locale.ROOT).startsWith("image/"))return null;
                    try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                        byte[] buf=new byte[8192];int n,total=0;
                        while((n=in.read(buf))!=-1){
                            total+=n;if(total>1024*1024)return null;
                            out.write(buf,0,n);
                        }
                        byte[] image=out.toByteArray();
                        BitmapFactory.Options opts=new BitmapFactory.Options();
                        opts.inJustDecodeBounds=true;
                        BitmapFactory.decodeByteArray(image,0,image.length,opts);
                        opts.inSampleSize=1;
                        while(opts.outWidth/opts.inSampleSize>500||opts.outHeight/opts.inSampleSize>500)
                            opts.inSampleSize*=2;
                        opts.inJustDecodeBounds=false;
                        return BitmapFactory.decodeByteArray(image,0,image.length,opts);
                    }
                }finally{c.disconnect();}
            }
        }catch(Exception ignored){}
        return null;
    }
    private boolean samePage(String a,String b) {
        if(a==null||b==null)return false;
        return a.split("#",2)[0].equals(b.split("#",2)[0]);
    }

    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private LinearLayout.LayoutParams lp(int width,int height) {
        return new LinearLayout.LayoutParams(width,height>0?dp(height):height);
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
        root.setPadding(dp(12),dp(18),dp(12),dp(10));
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
        save.setOnClickListener(v->{
            boolean found;
            synchronized(streamsMutex){found=!detectedStreams.isEmpty();}
            if(found)selectStreamToSave();
            else saveLink(currentUrl());
        });
        toolbar.addView(open,new LinearLayout.LayoutParams(0,dp(52),1));
        toolbar.addView(save,new LinearLayout.LayoutParams(0,dp(52),1));
        root.addView(toolbar,lp(-1,56));
        Button library=button("📁 保存済み動画を見る・再生・削除");
        library.setOnClickListener(v->startActivity(
            new Intent(this,SavedVideosActivity.class)));
        root.addView(library,lp(-1,49));
        pageLockButton=button("🔒 ページ固定: ON");
        pageLockButton.setOnClickListener(v->{
            pageLockEnabled=!pageLockEnabled;
            pinnedPageUrl=pageLockEnabled&&web!=null&&allowed(web.getUrl())?web.getUrl():null;
            pageLockButton.setText(pageLockEnabled?"🔒 ページ固定: ON":"🔓 ページ固定: OFF");
            display(pageLockEnabled?"ページ移動をブロックします。":"ページ固定を解除しました。");
        });
        root.addView(pageLockButton,lp(-1,46));
        streamButton=button("📺 再生中の配信を保存 (0)");
        streamButton.setOnClickListener(v->selectStreamToSave());
        root.addView(streamButton,lp(-1,48));
        freshButton=button("🔄 配信候補を更新・再検出");
        freshButton.setOnClickListener(v->reloadAndFindLatestStreams());
        root.addView(freshButton,lp(-1,46));
        status=new TextView(this);
        status.setText("SNSの共有メニューからも起動できます。");
        status.setTextColor(Color.DKGRAY);
        status.setTextSize(12);
        status.setGravity(Gravity.CENTER);
        status.setMaxLines(3);
        status.setTextIsSelectable(true);
        root.addView(status,lp(-1,58));
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress,lp(-1,7));
        stop=button("ダウンロードを中止");
        stop.setVisibility(View.GONE);
        stop.setOnClickListener(v->{
            DownloadService.cancelFromActivity(this);
            display("ダウンロードの中止を要求しました。");
        });
        root.addView(stop,lp(-1,43));
        LinearLayout diagnostics=new LinearLayout(this);
        diagnostics.setOrientation(LinearLayout.HORIZONTAL);
        Button showError=button("エラー詳細・コピー");
        showError.setOnClickListener(v->{
            SharedPreferences p=DownloadService.state(this);
            String summary=p.getString("error_summary","");
            String cause=p.getString("error_type","");
            String recovery=p.getString("recovery_hint","");
            String details=p.getString("error_detail","");
            String phase=p.getString("debug_stage","未記録");
            String lastLine=p.getString("debug_last_line","まだ出力がありません");
            String warning=p.getString("debug_warning","");
            long started=p.getLong("debug_started_at",0);
            long seconds=started>0?Math.max(0,(System.currentTimeMillis()-started)/1000):0;
            if(details.isEmpty())details="処理中または詳細ログがまだありません。";
            final String copyText="ShareSaveの診断情報\n"+
                "経過時間: "+seconds+" 秒\n"+
                "段階: "+phase+"\n"+
                "最終出力: "+lastLine+"\n"+
                (warning.isEmpty()?"":"警告: "+warning+"\n")+
                "エラー概要: "+summary+"\n"+
                (cause.isEmpty()?"":"判定: "+cause+"\n")+
                (recovery.isEmpty()?"":"推奨対応: "+recovery+"\n")+
                "詳細:\n"+details;
            TextView content=new TextView(this);
            content.setText(copyText);
            content.setTextSize(12);
            content.setTextIsSelectable(true);
            int pad=dp(16);content.setPadding(pad,pad,pad,pad);
            ScrollView scroller=new ScrollView(this);
            scroller.addView(content);
            new AlertDialog.Builder(this)
                .setTitle("ダウンロードのエラー詳細")
                .setView(scroller)
                .setPositiveButton("コピー",(d,which)->{
                    ClipboardManager clip=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    clip.setPrimaryClip(ClipData.newPlainText("ShareSave diagnostics",copyText));
                    display("エラー詳細をコピーしました。");
                }).setNegativeButton("閉じる",null).show();
        });
        diagnostics.addView(showError,new LinearLayout.LayoutParams(0,dp(46),1));
        Button updateEngine=button("yt-dlp更新");
        updateEngine.setOnClickListener(v->{
            try{
                startForegroundService(new Intent(this,DownloadService.class)
                    .setAction(DownloadService.ACTION_UPDATE));
                display("動画取得エンジンの更新を確認しています…");
            }catch(Exception e){display("更新開始に失敗: "+e.getMessage());}
        });
        diagnostics.addView(updateEngine,new LinearLayout.LayoutParams(0,dp(46),1));
        root.addView(diagnostics,lp(-1,50));
        web=new WebView(this);
        WebSettings s=web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        // true + reject onCreateWindow prevents target=_blank from replacing this page.
        s.setSupportMultipleWindows(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        // Observe requests without replacing, replaying, or caching WebView responses.
        ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebResourceRequest request){
                if("GET".equalsIgnoreCase(request.getMethod()))
                    observeMediaRequest(request.getUrl().toString(),
                        requestHeader(request.getRequestHeaders(),"Referer"),
                        requestHeader(request.getRequestHeaders(),"Origin"));
                return null;
            }
        });
        web.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                if("GET".equalsIgnoreCase(request.getMethod()))
                    observeMediaRequest(request.getUrl().toString(),
                        requestHeader(request.getRequestHeaders(),"Referer"),
                        requestHeader(request.getRequestHeaders(),"Origin"));
                return null; // preserve original playback and headers
            }
            @Override public boolean shouldOverrideUrlLoading(WebView w,WebResourceRequest r) {
                if(!r.isForMainFrame()) return false;
                String next=r.getUrl().toString();
                if(!allowed(next)) {
                    display("HTTPSの公開ページ以外は開けません。"); return true;
                }
                if(pageLockEnabled&&pinnedPageUrl!=null&&!samePage(pinnedPageUrl,next)) {
                    display("ページ固定中：広告などのページ移動をブロックしました。");
                    return true;
                }
                return false;
            }
            @Override public void onPageFinished(WebView w,String url) {
                if(allowed(url)) {
                    address.setText(url);
                    if(pageLockEnabled&&pinnedPageUrl==null)pinnedPageUrl=url;
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onCreateWindow(WebView view,boolean isDialog,
                                                      boolean isUserGesture,android.os.Message resultMsg){
                display("広告などの別ウィンドウをブロックしました。");
                return false;
            }
        });
        web.setDownloadListener((url,ua,disposition,mime,size)->{
            if(allowed(url)&&(directMedia(url)||mime!=null&&(mime.startsWith("video/")||mime.startsWith("image/"))))
                saveLink(url);
        });
        root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        SafeWindowLayout.apply(this,root,false);
        // Android 13+: optional permission, so progress appears in the notification shade.
        if(Build.VERSION.SDK_INT>=33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=
                android.content.pm.PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},908);
        }
        receive(getIntent());
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        receive(intent);
    }
    @Override public void onBackPressed() {
        if(web.canGoBack()){pinnedPageUrl=null;web.goBack();}
        else super.onBackPressed();
    }
    @Override protected void onResume(){
        super.onResume();
        downloadUiHandler.removeCallbacks(updateDownloadUi);
        downloadUiHandler.post(updateDownloadUi);
    }
    @Override protected void onPause(){
        downloadUiHandler.removeCallbacks(updateDownloadUi);
        super.onPause();
    }
    @Override protected void onDestroy() {
        // DownloadService remains alive when this screen is closed.
        downloadUiHandler.removeCallbacks(updateDownloadUi);
        previewPool.shutdownNow();
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
    private void reloadAndFindLatestStreams(){
        if(web==null||!allowed(web.getUrl())){
            display("動画ページを開いてから更新してください。");return;
        }
        pinnedPageUrl=null;
        resetDetectedStreams();
        web.reload();
        display("動画ページを更新しました。再生後、最新の配信候補を選んでください。");
    }
    private void openUrl(String raw) {
        String url=urlIn(raw);
        if(!allowed(url)){display("公開HTTPSのURLを入力してください。");return;}
        // Manual URL entry is trusted; pin once this page has loaded.
        pinnedPageUrl=null;
        resetDetectedStreams();
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
    private void saveStreams(ArrayList<Uri> streams) {
        if(streams==null||streams.isEmpty())return;
        if(DownloadService.isRunning()){
            display("既にダウンロード中です。完了後にもう一度お試しください。");
            return;
        }
        Intent service=new Intent(this,DownloadService.class)
            .setAction(DownloadService.ACTION_SHARE)
            .putParcelableArrayListExtra(DownloadService.EXTRA_URIS,streams);
        // Forward the URI grant to the service when invoked via Android Sharesheet.
        android.content.ClipData clips=null;
        for(Uri uri:streams){
            if(clips==null)clips=android.content.ClipData.newUri(
                getContentResolver(),"Shared media",uri);
            else clips.addItem(new android.content.ClipData.Item(uri));
        }
        if(clips!=null){
            service.setClipData(clips);
            service.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        try{
            startForegroundService(service);
            display("バックグラウンドで共有ファイルを保存します。");
        }catch(Exception e){display("保存を開始できませんでした: "+e.getMessage());}
    }
    private void saveLink(String raw){
        saveLinkWithContext(raw,null,null);
    }
    private void saveLinkWithContext(String raw,String ref){
        saveLinkWithContext(raw,ref,null);
    }
    private void saveLinkWithContext(String raw,String ref,String origin){
        String url=urlIn(raw);
        if(!allowed(url)){display("保存には公開HTTPSのURLが必要です。");return;}
        if(DownloadService.isRunning()){
            display("別の動画を保存中です。進捗は通知に表示されます。");
            return;
        }
        String referer=validReferer(ref);
        String mediaCookie=CookieManager.getInstance().getCookie(url);
        String refCookie=referer==null?null:CookieManager.getInstance().getCookie(referer);
        Intent service=new Intent(this,DownloadService.class)
            .setAction(DownloadService.ACTION_DOWNLOAD)
            .putExtra(DownloadService.EXTRA_URL,url)
            .putExtra(DownloadService.EXTRA_REFERER,referer)
            .putExtra(DownloadService.EXTRA_UA,web.getSettings().getUserAgentString())
            .putExtra(DownloadService.EXTRA_ORIGIN,validOrigin(origin))
            .putExtra(DownloadService.EXTRA_MEDIA_COOKIE,mediaCookie)
            .putExtra(DownloadService.EXTRA_REFERER_COOKIE,refCookie);
        try{
            startForegroundService(service);
            display("ダウンロードを開始しました。別のアプリに移動しても継続します。");
        }catch(Exception e){display("ダウンロードを開始できませんでした: "+e.getMessage());}
    }
}
