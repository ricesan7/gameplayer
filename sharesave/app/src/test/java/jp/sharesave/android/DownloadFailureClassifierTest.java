package jp.sharesave.android;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class DownloadFailureClassifierTest {
    @Test public void diagnosesAccessAndExpiry(){
        assertTrue(DownloadFailureClassifier.evaluate("ERROR: HTTP Error 403: Forbidden",null).category.contains("403"));
        assertTrue(DownloadFailureClassifier.evaluate("HTTP Error 404",null).guidance.contains("再"));
        assertTrue(DownloadFailureClassifier.evaluate("HTTP Error 401",null).category.contains("認証"));
        assertTrue(DownloadFailureClassifier.evaluate("HTTP Error 429",null).guidance.contains("時間"));
    }
    @Test public void diagnosesStallAndDrm(){
        assertTrue(DownloadFailureClassifier.evaluate("4分以上応答しませんでした",null).category.contains("タイムアウト"));
        assertTrue(DownloadFailureClassifier.evaluate("DRM restricted",null).guidance.contains("DRM"));
    }
    @Test public void consumesBufferedEngineError(){
        assertTrue(DownloadFailureClassifier.evaluate("yt-dlp failed",
            Arrays.asList("WARNING: version old","ERROR: HTTP Error 403")).category.contains("403"));
        assertEquals("原因未判定",DownloadFailureClassifier.evaluate("unexpected error",
            Collections.singletonList("unknown")).category);
    }
}
