package jp.sharesave.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
    private static final long LIMIT=500L*1024*1024;
    private static final Pattern HTTPS=Pattern.compile("https://[^\\s<>\\\"']+",Pattern.CASE_INSENSITIVE);
    private final ExecutorService pool=Executors.newSingleThreadExecutor();
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
    private final Object streamsMutex=new Object();
    private final LinkedHashMap<String,StreamCandidate> detectedStreams=new LinkedHashMap<>();
    private static final int MAX_CANDIDATES=15;
    private static class StreamCandidate {
        final String url;
        final String kind;
        StreamCandidate(String url,String kind){this.url=url;this.kind=kind;}
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
    private void observeMediaRequest(String url){
        if(!allowed(url))return;
        String kind=StreamUrlMatcher.mediaKind(url);
        if(kind==null)return;
        synchronized(streamsMutex){
            if(detectedStreams.containsKey(url))return;
            // Drop oldest candidate when adaptive bitrates produce many variants.
            if(detectedStreams.size()>=MAX_CANDIDATES){
                String oldest=detectedStreams.keySet().iterator().next();
                detectedStreams.remove(oldest);
            }
            detectedStreams.put(url,new StreamCandidate(url,kind));
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
        web.evaluateJavascript(PAGE_PREVIEW_SCRIPT,result->{
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

        for(int index=0;index<candidates.size();index++){
            StreamCandidate c=candidates.get(index);
            String poster=fallbackPoster;
            String title=pageTitle;
            double duration=0;
            boolean videoMatched=false;
            for(JSONObject video:videos){
                JSONArray arr=video.optJSONArray("urls");
                boolean matched=false;
                if(arr!=null)for(int k=0;k<arr.length();k++)
                    if(c.url.equals(arr.optString(k,"")))matched=true;
                if(matched){
                    videoMatched=true;
                    String candidateTitle=video.optString("title","");
                    if(!candidateTitle.isEmpty())title=candidateTitle;
                    String specificPoster=video.optString("poster","");
                    if(allowed(specificPoster))poster=specificPoster;
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
            row.addView(detail,new LinearLayout.LayoutParams(0,-2,1));

            row.setClickable(true);
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            row.setOnClickListener(v->{
                dialog.dismiss();
                saveLinkWithContext(c.url,safePage);
            });
            list.addView(row,lp(-1,-2));
            View divider=new View(this);
            divider.setBackgroundColor(Color.rgb(222,226,230));
            list.addView(divider,lp(-1,1));

            final String caption=videoMatched?"動画サムネイル":"ページ代表画像（動画との一致未確認）";
            previewPool.submit(()->{
                StreamDetails.SizeInfo detailInfo=StreamDetails.inspect(c.url,c.kind,safePage,userAgent,seconds);
                Bitmap bitmap=loadThumbnail(imageUrl,safePage,userAgent);
                runOnUiThread(()->{
                    if(!dialog.isShowing()||isFinishing()||isDestroyed())return;
                    size.setText(detailInfo.label());
                    if(bitmap!=null){
                        thumbnail.setImageBitmap(bitmap);
                        thumbnail.setContentDescription(caption);
                    }else{
                        thumbnail.setContentDescription("サムネイル未取得");
                    }
                });
            });
        }
        dialog.show();
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
        save.setOnClickListener(v->saveLink(currentUrl()));
        toolbar.addView(open,new LinearLayout.LayoutParams(0,dp(52),1));
        toolbar.addView(save,new LinearLayout.LayoutParams(0,dp(52),1));
        root.addView(toolbar,lp(-1,56));
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
        // true + reject onCreateWindow prevents target=_blank from replacing this page.
        s.setSupportMultipleWindows(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        // Observe requests without replacing, replaying, or caching WebView responses.
        ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebResourceRequest request){
                if("GET".equalsIgnoreCase(request.getMethod()))
                    observeMediaRequest(request.getUrl().toString());
                return null;
            }
        });
        web.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                if("GET".equalsIgnoreCase(request.getMethod()))
                    observeMediaRequest(request.getUrl().toString());
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
    @Override protected void onDestroy() {
        cancel.set(true);
        try {if(processId!=null)YoutubeDL.getInstance().destroyProcessById(processId);}catch(Exception ignored){}
        pool.shutdownNow();
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
        saveLinkWithContext(raw,null);
    }
    private void saveLinkWithContext(String raw,String referringPage) {
        String link=urlIn(raw);
        if(!allowed(link)){display("保存には公開HTTPSのURLが必要です。");return;}
        final String browserUserAgent=web.getSettings().getUserAgentString();
        final String safeReferer=allowed(referringPage)?referringPage:null;
        beginJob();AtomicBoolean stopFlag=cancel;
        pool.submit(()->{
            try {
                display("動画の取得を確認しています…");
                if(directMedia(link))downloadDirect(link,stopFlag);
                else downloadPost(link,stopFlag,safeReferer,browserUserAgent);
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
    private void downloadPost(String link,AtomicBoolean stopFlag,String referer,String userAgent) throws Exception {
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
            // HLS and DASH are fragmented streams, not browser-cache files.
            req.addOption("--concurrent-fragments",3);
            if(referer!=null)req.addOption("--referer",referer);
            if(userAgent!=null&&!userAgent.isEmpty())req.addOption("--user-agent",userAgent);
            req.addOption("--retries",2);
            req.addOption("--max-filesize","500M");
            req.addOption("-f","bv*+ba/b");
            req.addOption("--merge-output-format","mp4");
            req.addOption("--restrict-filenames");
            req.addOption("-o",new File(temp,"%(id)s.%(ext)s").getAbsolutePath());
            YoutubeDL.getInstance().execute(req,processId,(percent,eta,line)->{
                if(!stopFlag.get())runOnUiThread(()->{
                    progress.setIndeterminate(false);
                    progress.setProgress(Math.max(0,Math.min(100,Math.round(percent))));
                    status.setText("ダウンロード中 "+Math.round(percent)+"%");
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
