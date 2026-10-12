package jp.sharesave.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

/** Lightweight in-app player for MediaStore video content URIs. */
public class VideoPlayerActivity extends Activity {
    static final String EXTRA_URI="jp.sharesave.android.VIDEO_URI";
    static final String EXTRA_TITLE="jp.sharesave.android.VIDEO_TITLE";
    private VideoView video;
    private Uri mediaUri;
    private int position;
    private boolean playerReady=false;
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        if(state!=null)position=state.getInt("play_position",0);
        String raw=getIntent().getStringExtra(EXTRA_URI);
        if(raw==null||!raw.startsWith("content://media/")){finish();return;}
        mediaUri=Uri.parse(raw);

        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        LinearLayout toolbar=new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        Button back=new Button(this);
        back.setText("← 一覧");back.setAllCaps(false);
        back.setOnClickListener(v->finish());
        toolbar.addView(back,new LinearLayout.LayoutParams(dp(102),dp(48)));

        TextView title=new TextView(this);
        title.setText(getIntent().getStringExtra(EXTRA_TITLE));
        title.setTextSize(14);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTextColor(Color.WHITE);
        toolbar.addView(title,new LinearLayout.LayoutParams(0,dp(45),1));

        Button external=new Button(this);
        external.setText("外部で再生");external.setTextSize(11);external.setAllCaps(false);
        external.setOnClickListener(v->openExternally());
        toolbar.addView(external,new LinearLayout.LayoutParams(dp(112),dp(48)));
        root.addView(toolbar,new LinearLayout.LayoutParams(-1,dp(54)));

        video=new VideoView(this);
        MediaController controller=new MediaController(this);
        controller.setAnchorView(video);
        video.setMediaController(controller);
        video.setOnPreparedListener(mp->{
            playerReady=true;
            if(position>0)video.seekTo(position);
            video.start();
            controller.show(2200);
        });
        video.setOnErrorListener((mp,what,extra)->{
            new AlertDialog.Builder(this).setTitle("再生できませんでした")
                .setMessage("この端末で対応していない動画形式やコーデックの可能性があります。別の再生アプリで開くこともできます。")
                .setPositiveButton("外部で再生",(d,w)->openExternally())
                .setNegativeButton("戻る",(d,w)->finish()).show();
            return true;
        });
        root.addView(video,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        video.setVideoURI(mediaUri);
    }

    private void openExternally(){
        try{
            Intent i=new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(mediaUri,"video/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i,"再生アプリを選択"));
        }catch(Exception e){
            new AlertDialog.Builder(this).setMessage("動画を開けるアプリが見つかりません。")
                .setPositiveButton("OK",null).show();
        }
    }
    @Override protected void onPause(){
        if(video!=null&&playerReady){
            position=video.getCurrentPosition();
            video.pause();
        }
        super.onPause();
    }
    @Override protected void onResume(){
        super.onResume();
        if(video!=null&&playerReady&&position>0){
            video.seekTo(position);
        }
    }
    @Override public void onSaveInstanceState(Bundle outState){
        outState.putInt("play_position",position);
        super.onSaveInstanceState(outState);
    }
    @Override protected void onDestroy(){
        if(video!=null)video.stopPlayback();
        super.onDestroy();
    }
}
