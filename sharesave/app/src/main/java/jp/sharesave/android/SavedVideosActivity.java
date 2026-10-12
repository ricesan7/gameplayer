package jp.sharesave.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.RecoverableSecurityException;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Lists only the video media created under Movies/ShareSave by this application. */
public class SavedVideosActivity extends Activity {
    private static final String FOLDER=Environment.DIRECTORY_MOVIES+"/ShareSave/";
    private static final int REQUEST_DELETE=4101;
    private final ExecutorService loader=Executors.newSingleThreadExecutor();
    private final ExecutorService thumbnails=Executors.newFixedThreadPool(2);
    private final AtomicInteger generation=new AtomicInteger();
    private final ArrayList<VideoItem> items=new ArrayList<>();
    private final LruCache<Long,Bitmap> thumbCache=new LruCache<Long,Bitmap>(8*1024*1024){
        @Override protected int sizeOf(Long key,Bitmap value){return value.getByteCount();}
    };
    private VideoAdapter adapter;
    private TextView caption;

    private static final class VideoItem {
        final long id,bytes,duration,added;
        final String name;
        final Uri uri;
        VideoItem(long id,String name,long bytes,long duration,long added){
            this.id=id;this.name=name;this.bytes=bytes;this.duration=duration;this.added=added;
            this.uri=ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,id);
        }
    }
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12),dp(12),dp(12),dp(8));
        root.setBackgroundColor(Color.rgb(250,250,252));

        LinearLayout actions=new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button back=new Button(this);back.setAllCaps(false);back.setText("← 戻る");
        back.setOnClickListener(v->finish());
        actions.addView(back,new LinearLayout.LayoutParams(0,dp(49),1));
        TextView heading=new TextView(this);
        heading.setText("保存済み動画");heading.setTextSize(20);
        heading.setTextColor(Color.rgb(35,75,160));heading.setGravity(Gravity.CENTER);
        actions.addView(heading,new LinearLayout.LayoutParams(0,dp(49),2));
        Button refresh=new Button(this);refresh.setAllCaps(false);refresh.setText("更新");
        refresh.setOnClickListener(v->loadVideos());
        actions.addView(refresh,new LinearLayout.LayoutParams(0,dp(49),1));
        root.addView(actions,new LinearLayout.LayoutParams(-1,dp(58)));

        caption=new TextView(this);
        caption.setText("保存済み動画を読み込み中…");
        caption.setTextSize(12);caption.setGravity(Gravity.CENTER_VERTICAL);
        caption.setTextColor(Color.DKGRAY);
        root.addView(caption,new LinearLayout.LayoutParams(-1,dp(44)));

        ListView list=new ListView(this);
        list.setDividerHeight(dp(1));
        adapter=new VideoAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent,view,position,id)->play(items.get(position)));
        list.setEmptyView(null);
        root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        SafeWindowLayout.apply(this,root,false);
        loadVideos();
    }
    @Override protected void onResume(){
        super.onResume();
        if(adapter!=null)loadVideos();
    }
    @Override protected void onDestroy(){
        generation.incrementAndGet();
        loader.shutdownNow();
        thumbnails.shutdownNow();
        super.onDestroy();
    }
    private void loadVideos(){
        int token=generation.incrementAndGet();
        caption.setText("保存済み動画を読み込み中…");
        loader.submit(()->{
            ArrayList<VideoItem> found=new ArrayList<>();
            String failure=null;
            String[] cols={
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.DATE_ADDED
            };
            // Query just this directory; never enumerate or delete other videos.
            String where=MediaStore.MediaColumns.RELATIVE_PATH+"=? AND "+
                         MediaStore.MediaColumns.IS_PENDING+"=0";
            try(Cursor c=getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                     cols,where,new String[]{FOLDER},MediaStore.Video.Media.DATE_ADDED+" DESC")){
                if(c!=null){
                    int idCol=c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                    int nameCol=c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
                    int sizeCol=c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
                    int durationCol=c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
                    int addedCol=c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
                    while(c.moveToNext()){
                        found.add(new VideoItem(c.getLong(idCol),c.getString(nameCol),
                           c.isNull(sizeCol)?-1:c.getLong(sizeCol),
                           c.isNull(durationCol)?-1:c.getLong(durationCol),
                           c.isNull(addedCol)?0:c.getLong(addedCol)));
                    }
                }else failure="保存済み動画を読み込めませんでした。";
            }catch(Exception e){failure="読み込みに失敗しました。保存先へのアクセスを確認してください。";}
            final String resultError=failure;
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed()||token!=generation.get())return;
                items.clear();items.addAll(found);
                adapter.notifyDataSetChanged();
                if(resultError!=null)caption.setText(resultError);
                else if(items.isEmpty())caption.setText("保存済み動画はありません（Movies/ShareSave）");
                else caption.setText(items.size()+" 件の動画　｜　Movies/ShareSave　（新しい順）");
            });
        });
    }
    private static String durationLabel(long millis){
        if(millis<=0)return "時間不明";
        long secs=millis/1000,mins=secs/60,hours=mins/60;
        if(hours>0)return String.format(Locale.JAPAN,"%d:%02d:%02d",hours,mins%60,secs%60);
        return String.format(Locale.JAPAN,"%d:%02d",mins,secs%60);
    }
    private static String sizeLabel(long bytes){
        return bytes>=0?StreamDetails.formatBytes(bytes):"容量不明";
    }
    private void play(VideoItem item){
        Intent intent=new Intent(this,VideoPlayerActivity.class);
        intent.putExtra(VideoPlayerActivity.EXTRA_URI,item.uri.toString());
        intent.putExtra(VideoPlayerActivity.EXTRA_TITLE,item.name);
        startActivity(intent);
    }
    private void askDelete(VideoItem item){
        new AlertDialog.Builder(this)
            .setTitle("動画を削除")
            .setMessage("「"+item.name+"」を端末から完全に削除しますか？\nギャラリーの動画も削除されます。この操作は取り消せません。")
            .setNegativeButton("キャンセル",null)
            .setPositiveButton("削除",(dialog,which)->delete(item))
            .show();
    }
    private void delete(VideoItem item){
        // Restrict any deletion to a video actually returned in this folder's list.
        boolean listed=false;
        for(VideoItem v:items)if(v.id==item.id){listed=true;break;}
        if(!listed)return;
        try{
            int removed=getContentResolver().delete(item.uri,null,null);
            if(removed==0)caption.setText("ファイルは既に削除されている可能性があります。");
            loadVideos();
        }catch(SecurityException e){
            try{
                android.content.IntentSender sender;
                if(Build.VERSION.SDK_INT>=30){
                    sender=MediaStore.createDeleteRequest(getContentResolver(),
                            Collections.singletonList(item.uri)).getIntentSender();
                }else if(e instanceof RecoverableSecurityException){
                    sender=((RecoverableSecurityException)e).getUserAction().getActionIntent().getIntentSender();
                }else throw e;
                startIntentSenderForResult(sender,REQUEST_DELETE,null,0,0,0);
            }catch(Exception ex){
                new AlertDialog.Builder(this).setMessage("削除権限を取得できませんでした。")
                    .setPositiveButton("OK",null).show();
            }
        }catch(Exception e){
            new AlertDialog.Builder(this).setMessage("削除できませんでした。")
                .setPositiveButton("OK",null).show();
        }
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==REQUEST_DELETE)loadVideos();
    }
    private class VideoAdapter extends BaseAdapter {
        @Override public int getCount(){return items.size();}
        @Override public Object getItem(int position){return items.get(position);}
        @Override public long getItemId(int position){return items.get(position).id;}
        class Holder{
            ImageView image;
            TextView name,info;
            Button delete;
        }
        @Override public View getView(int position,View recycled,ViewGroup parent){
            VideoItem item=items.get(position);
            LinearLayout row;
            Holder h;
            if(recycled instanceof LinearLayout && recycled.getTag() instanceof Holder){
                row=(LinearLayout)recycled;h=(Holder)row.getTag();
            }else{
                row=new LinearLayout(SavedVideosActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(4),dp(8),dp(4),dp(8));
                h=new Holder();
                h.image=new ImageView(SavedVideosActivity.this);
                h.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                h.image.setBackgroundColor(Color.rgb(223,229,237));
                row.addView(h.image,new LinearLayout.LayoutParams(dp(112),dp(72)));
                LinearLayout texts=new LinearLayout(SavedVideosActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setPadding(dp(10),0,dp(6),0);
                h.name=new TextView(SavedVideosActivity.this);
                h.name.setTextColor(Color.BLACK);h.name.setTextSize(14);h.name.setMaxLines(2);
                texts.addView(h.name);
                h.info=new TextView(SavedVideosActivity.this);
                h.info.setTextColor(Color.DKGRAY);h.info.setTextSize(11);h.info.setMaxLines(2);
                texts.addView(h.info);
                row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
                h.delete=new Button(SavedVideosActivity.this);
                h.delete.setText("削除");h.delete.setTextSize(12);h.delete.setAllCaps(false);
                h.delete.setFocusable(false);
                row.addView(h.delete,new LinearLayout.LayoutParams(dp(66),dp(48)));
                row.setTag(h);
            }
            h.name.setText(item.name);
            String date=item.added>0?DateFormat.getDateInstance(DateFormat.SHORT,Locale.JAPAN)
                         .format(new Date(item.added*1000)):"";
            h.info.setText(sizeLabel(item.bytes)+" · "+durationLabel(item.duration)+"\n"+date);
            h.delete.setOnClickListener(v->askDelete(item));
            row.setOnClickListener(v->play(item));
            h.image.setTag(item.id);
            Bitmap cached=thumbCache.get(item.id);
            if(cached!=null){h.image.setImageBitmap(cached);}
            else{
                h.image.setImageResource(android.R.drawable.ic_media_play);
                final ImageView image=h.image;
                thumbnails.submit(()->{
                    Bitmap bitmap=null;
                    try{
                        bitmap=getContentResolver().loadThumbnail(item.uri,
                            new Size(dp(224),dp(144)),null);
                    }catch(Exception ignored){}
                    if(bitmap!=null){
                        thumbCache.put(item.id,bitmap);
                        final Bitmap ready=bitmap;
                        runOnUiThread(()->{
                            if(!isDestroyed()&&Long.valueOf(item.id).equals(image.getTag()))
                                image.setImageBitmap(ready);
                        });
                    }
                });
            }
            return row;
        }
    }
}
