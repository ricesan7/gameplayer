package jp.sharesave.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

/**
 * Local video player with a persistent seek bar, rotation-triggered immersive
 * landscape, and manual full-screen toggle. In-place orientation changes keep
 * the VideoView rather than restarting playback on every sensor rotation.
 */
public class VideoPlayerActivity extends Activity {
    static final String EXTRA_URI="jp.sharesave.android.VIDEO_URI";
    static final String EXTRA_TITLE="jp.sharesave.android.VIDEO_TITLE";
    private VideoView video;
    private FrameLayout root;
    private LinearLayout header;
    private Button playPause;
    private Button fullScreenButton;
    private SeekBar seek;
    private TextView currentTime;
    private TextView totalTime;
    private Uri mediaUri;
    private boolean prepared=false,dragging=false,manualFullscreen=false;
    private boolean resumeAfterPause=false;
    private int pendingPosition=0;
    private final Handler ticker=new Handler(Looper.getMainLooper());
    private final Runnable updateProgress=new Runnable(){
        @Override public void run(){
            if(video!=null&&prepared){
                if(!dragging){
                    int duration=Math.max(0,video.getDuration());
                    int pos=Math.max(0,video.getCurrentPosition());
                    seek.setMax(Math.max(1,duration));
                    seek.setProgress(Math.min(pos,seek.getMax()));
                    currentTime.setText(formatTime(pos));
                    totalTime.setText(formatTime(duration));
                }
                updatePlayButton();
            }
            ticker.postDelayed(this,400);
        }
    };

    private int dp(int n){
        return (int)(n*getResources().getDisplayMetrics().density+0.5f);
    }
    static String formatTime(long milliseconds){
        if(milliseconds<0)milliseconds=0;
        long sec=milliseconds/1000;
        long hour=sec/3600;
        long minute=(sec/60)%60;
        long second=sec%60;
        if(hour>0)return String.format(java.util.Locale.US,
                "%d:%02d:%02d",hour,minute,second);
        return String.format(java.util.Locale.US,"%02d:%02d",minute,second);
    }
    private GradientDrawable shade(int color,int radius){
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(color);bg.setCornerRadius(dp(radius));return bg;
    }
    private Button control(String label){
        Button b=new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setMinHeight(dp(44));
        b.setBackground(shade(Color.rgb(42,50,68),8));
        return b;
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        if(state!=null){
            pendingPosition=state.getInt("play_position",0);
            manualFullscreen=state.getBoolean("manual_fullscreen",false);
        }
        String raw=getIntent().getStringExtra(EXTRA_URI);
        if(raw==null||!raw.startsWith("content://media/")){
            finish();return;
        }
        mediaUri=Uri.parse(raw);
        root=new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        video=new VideoView(this);
        video.setBackgroundColor(Color.BLACK);
        root.addView(video,new FrameLayout.LayoutParams(-1,-1));

        header=new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(dp(8),dp(4),dp(8),dp(4));
        header.setBackgroundColor(Color.argb(218,19,24,34));
        Button back=control("← 一覧");
        back.setOnClickListener(v->finish());
        header.addView(back,new LinearLayout.LayoutParams(dp(94),dp(46)));

        TextView name=new TextView(this);
        name.setText(getIntent().getStringExtra(EXTRA_TITLE));
        name.setTextSize(14);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextColor(Color.WHITE);
        name.setPadding(dp(10),0,dp(8),0);
        header.addView(name,new LinearLayout.LayoutParams(0,dp(48),1));

        Button external=control("外部で再生");
        external.setOnClickListener(v->openExternally());
        header.addView(external,new LinearLayout.LayoutParams(dp(110),dp(46)));
        FrameLayout.LayoutParams headParams=new FrameLayout.LayoutParams(-1,dp(58),Gravity.TOP);
        root.addView(header,headParams);

        LinearLayout controls=new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(12),dp(10),dp(12),dp(12));
        controls.setBackgroundColor(Color.argb(226,14,19,29));
        LinearLayout timeline=new LinearLayout(this);
        timeline.setOrientation(LinearLayout.HORIZONTAL);
        timeline.setGravity(Gravity.CENTER_VERTICAL);
        currentTime=new TextView(this);
        currentTime.setText("00:00");
        currentTime.setTextSize(12);
        currentTime.setTextColor(Color.WHITE);
        timeline.addView(currentTime,new LinearLayout.LayoutParams(dp(50),dp(40)));
        seek=new SeekBar(this);
        seek.setMax(1);
        seek.setProgress(0);
        seek.setContentDescription("動画の再生位置");
        timeline.addView(seek,new LinearLayout.LayoutParams(0,dp(48),1));
        totalTime=new TextView(this);
        totalTime.setText("00:00");
        totalTime.setTextSize(12);
        totalTime.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        totalTime.setTextColor(Color.WHITE);
        timeline.addView(totalTime,new LinearLayout.LayoutParams(dp(58),dp(40)));
        controls.addView(timeline,new LinearLayout.LayoutParams(-1,dp(50)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onStartTrackingTouch(SeekBar bar){dragging=true;}
            @Override public void onProgressChanged(SeekBar bar,int value,boolean fromUser){
                if(fromUser)currentTime.setText(formatTime(value));
            }
            @Override public void onStopTrackingTouch(SeekBar bar){
                int target=bar.getProgress();
                pendingPosition=target;
                if(prepared){
                    video.seekTo(target);
                    if(!video.isPlaying())resumeAfterPause=false;
                }
                dragging=false;
            }
        });

        LinearLayout actions=new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        playPause=control("▶ 再生");
        playPause.setOnClickListener(v->{
            if(!prepared)return;
            if(video.isPlaying()){video.pause();resumeAfterPause=false;}
            else{video.start();resumeAfterPause=true;}
            updatePlayButton();
        });
        LinearLayout.LayoutParams playParams=new LinearLayout.LayoutParams(0,dp(46),1);
        playParams.setMargins(0,0,dp(8),0);
        actions.addView(playPause,playParams);

        fullScreenButton=control("⛶ 全画面");
        fullScreenButton.setOnClickListener(v->{
            manualFullscreen=!manualFullscreen;
            updateDisplayMode();
        });
        actions.addView(fullScreenButton,new LinearLayout.LayoutParams(0,dp(46),1));
        controls.addView(actions,new LinearLayout.LayoutParams(-1,dp(48)));
        FrameLayout.LayoutParams bottomParams=new FrameLayout.LayoutParams(
                -1,dp(124),Gravity.BOTTOM);
        root.addView(controls,bottomParams);

        setContentView(root);
        SafeWindowLayout.apply(this,root,true);
        updateDisplayMode();
        video.setOnClickListener(v->{
            if(!prepared)return;
            if(video.isPlaying()){video.pause();resumeAfterPause=false;}
            else{video.start();resumeAfterPause=true;}
            updatePlayButton();
        });
        video.setOnPreparedListener(mp->{
            prepared=true;
            int duration=Math.max(0,video.getDuration());
            seek.setMax(Math.max(1,duration));
            totalTime.setText(formatTime(duration));
            if(pendingPosition>0)video.seekTo(Math.min(pendingPosition,duration));
            video.start();resumeAfterPause=true;
            updatePlayButton();
        });
        video.setOnCompletionListener(mp->{
            resumeAfterPause=false;
            updatePlayButton();
        });
        video.setOnErrorListener((mp,what,extra)->{
            prepared=false;
            new AlertDialog.Builder(this)
                .setTitle("再生できませんでした")
                .setMessage("動画形式・コーデックが端末に対応していない可能性があります。外部プレイヤーでも開けます。")
                .setPositiveButton("外部で再生",(d,w)->openExternally())
                .setNegativeButton("戻る",(d,w)->finish())
                .show();
            return true;
        });
        video.setVideoURI(mediaUri);
    }
    private void updatePlayButton(){
        if(playPause!=null)playPause.setText(
                video!=null&&prepared&&video.isPlaying()?"Ⅱ 一時停止":"▶ 再生");
    }
    private void updateDisplayMode(){
        boolean landscape=getResources().getConfiguration().orientation==
                Configuration.ORIENTATION_LANDSCAPE;
        boolean fullscreen=manualFullscreen||landscape;
        if(header!=null)header.setVisibility(fullscreen?View.GONE:View.VISIBLE);
        if(fullScreenButton!=null)fullScreenButton.setText(fullscreen?"⛶ 全画面解除":"⛶ 全画面");
        SafeWindowLayout.immersive(this,fullscreen,true);
    }
    @Override public void onConfigurationChanged(Configuration configuration){
        super.onConfigurationChanged(configuration);
        // A physical landscape rotation activates immersive playback automatically.
        updateDisplayMode();
    }
    @Override public void onBackPressed(){
        if(manualFullscreen){
            manualFullscreen=false;
            updateDisplayMode();
        }else super.onBackPressed();
    }
    private void openExternally(){
        try{
            Intent intent=new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(mediaUri,"video/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent,"再生アプリを選択"));
        }catch(Exception e){
            new AlertDialog.Builder(this).setMessage("動画を開けるアプリが見つかりません。")
                .setPositiveButton("OK",null).show();
        }
    }
    @Override protected void onResume(){
        super.onResume();
        ticker.removeCallbacks(updateProgress);
        ticker.post(updateProgress);
        updateDisplayMode();
        if(prepared&&resumeAfterPause)video.start();
    }
    @Override protected void onPause(){
        ticker.removeCallbacks(updateProgress);
        if(video!=null&&prepared){
            pendingPosition=video.getCurrentPosition();
            resumeAfterPause=video.isPlaying();
            video.pause();
        }
        super.onPause();
    }
    @Override public void onSaveInstanceState(Bundle state){
        state.putInt("play_position",video!=null&&prepared?
            video.getCurrentPosition():pendingPosition);
        state.putBoolean("manual_fullscreen",manualFullscreen);
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy(){
        ticker.removeCallbacks(updateProgress);
        if(video!=null)video.stopPlayback();
        super.onDestroy();
    }
}
