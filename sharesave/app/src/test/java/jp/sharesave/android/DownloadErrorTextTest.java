package jp.sharesave.android;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class DownloadErrorTextTest {
    @Test public void prefersActualErrorOverOldVersionWarning(){
        String warning="WARNING: Your yt-dlp version (2025.11.12) is older than 90 days!";
        String error="ERROR: HTTP Error 403: Forbidden";
        assertEquals(error,DownloadErrorText.select(warning+"\n"+error,Arrays.asList(warning,error)));
    }
    @Test public void neverPresentOnlyVersionWarningAsRootCause(){
        String warning="WARNING: Your yt-dlp version (2025.11.12) is older than 90 days!";
        assertTrue(DownloadErrorText.select(warning,Collections.singletonList(warning)).contains("原因を特定できません"));
    }
    @Test public void removesSensitiveUrlsAndAuthValues(){
        String logs=DownloadErrorText.details("error at https://example.org/file.m3u8?token=abc",
            Arrays.asList("Authorization: Bearer token","ERROR: HTTP Error 403"));
        assertFalse(logs.contains("token=abc"));
        assertFalse(logs.contains("Bearer token"));
        assertTrue(logs.contains("HTTP Error 403"));
    }
}
