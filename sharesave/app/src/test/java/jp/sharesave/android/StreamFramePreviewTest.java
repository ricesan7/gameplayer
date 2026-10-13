package jp.sharesave.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class StreamFramePreviewTest {
    @Test public void reportsSignedUrlAccessFailuresWithoutLeakingUrl(){
        String ffmpeg="https://cdn.example.com/playlist.m3u8?token=SUPER_SECRET: HTTP error 403 Forbidden";
        String result=StreamFramePreview.reason(ffmpeg);
        assertTrue(result.contains("403"));
        assertFalse(result.contains("SUPER_SECRET"));
    }
    @Test public void reportsCommonPreviewErrors(){
        assertTrue(StreamFramePreview.reason("Connection timed out").contains("接続"));
        assertTrue(StreamFramePreview.reason("Invalid data found when processing input").contains("動画形式"));
        assertTrue(StreamFramePreview.reason("protocol not on whitelist").contains("通信プロトコル"));
        assertTrue(StreamFramePreview.reason("SSL certificate error").contains("TLS"));
    }
    @Test public void reportsNoDecoderOutput(){
        assertFalse(StreamFramePreview.reason("").isEmpty());
    }
}
