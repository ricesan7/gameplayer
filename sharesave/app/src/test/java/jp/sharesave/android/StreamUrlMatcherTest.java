package jp.sharesave.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class StreamUrlMatcherTest {
    @Test public void recognizesSignedHlsAndDash(){
        assertEquals("HLS (m3u8)",StreamUrlMatcher.mediaKind("https://cdn.example.com/master.M3U8?expires=1&token=a"));
        assertEquals("DASH (mpd)",StreamUrlMatcher.mediaKind("https://cdn.example.com/live/manifest.mpd?sig=abc"));
    }
    @Test public void recognizesDirectVideoOnly(){
        assertEquals("MP4",StreamUrlMatcher.mediaKind("https://example.com/video.mp4"));
        assertEquals("WebM",StreamUrlMatcher.mediaKind("https://example.com/a.webm"));
    }
    @Test public void ignoresFragmentsAndUnsafeLinks(){
        assertNull(StreamUrlMatcher.mediaKind("https://site.test/seg-100.ts"));
        assertNull(StreamUrlMatcher.mediaKind("https://site.test/videopage.html?file=abc.m3u8"));
        assertNull(StreamUrlMatcher.mediaKind("http://example.com/video.m3u8"));
        assertNull(StreamUrlMatcher.mediaKind("blob:https://example.com/token"));
        assertNull(StreamUrlMatcher.mediaKind("https://example.com/fake.m3u8.jpg"));
    }
}
