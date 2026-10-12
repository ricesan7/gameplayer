package jp.sharesave.android;

import java.util.Locale;

/**
 * Maps yt-dlp output into user-facing stages. The Android downloader library
 * hard-codes 99% whenever it sees an FFmpeg "size=" line. That is NOT actual
 * file-download completion and must not be shown as such.
 */
final class DownloadProgress {
    static final class State {
        final String stage;
        final int percent;
        final boolean postProcessing;
        State(String stage,int percent,boolean postProcessing){
            this.stage=stage;this.percent=percent;this.postProcessing=postProcessing;
        }
    }
    private boolean inPostProcessing=false;
    private int lastDownloadPercent=0;

    static boolean isPostProcessingLog(String message){
        if(message==null)return false;
        String s=message.toLowerCase(Locale.ROOT).trim();
        return s.contains("[merger]")||
            s.contains("[videoremuxer]")||
            s.contains("[videoconvertor]")||
            s.contains("[fixup")||
            s.contains("[extractaudio]")||
            s.contains("[ffmpeg]")||
            s.contains("merging formats into")||
            s.startsWith("size=")||
            s.contains("time=")&&s.contains("size=") ||
            s.contains("frame=")&&s.contains("fps=");
    }
    State accept(float libraryPercent,String line){
        String raw=line==null?"":line;
        String lower=raw.toLowerCase(Locale.ROOT);
        if(isPostProcessingLog(raw))inPostProcessing=true;
        if(lower.startsWith("[download]") && lower.contains("100%"))
            lastDownloadPercent=100;
        // The wrapper reports 99% for FFmpeg's "size=" output regardless
        // of completion. Detect phase from the log line, not the wrapper %.
        if(inPostProcessing){
            return new State("動画と音声を結合・最終処理中（完了率は取得できません）",
                -1,true);
        }
        if(libraryPercent>=0f && libraryPercent<=100f){
            int pct=Math.max(0,Math.min(100,Math.round(libraryPercent)));
            lastDownloadPercent=Math.max(lastDownloadPercent,pct);
        }
        if(lastDownloadPercent>=100){
            return new State("動画データ取得完了・結合処理を確認中…",-1,false);
        }
        // Reserve completion for media-store publication, not yt-dlp.
        int display=Math.max(0,Math.min(90,(int)Math.round(lastDownloadPercent*0.9)));
        return new State("動画データを取得中 "+lastDownloadPercent+"%",display,false);
    }
    boolean isPostProcessing(){return inPostProcessing;}
}
