package jp.sharesave.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class StreamDetailsTest {
    @Test public void exactSizesAreReadable(){
        assertEquals("0 B",StreamDetails.formatBytes(0));
        assertEquals("1.0 KB",StreamDetails.formatBytes(1024));
        assertEquals("2.5 MB",StreamDetails.formatBytes(2621440));
        assertEquals("容量 4.0 MB",new StreamDetails.SizeInfo(4*1024*1024,false).label());
        assertEquals("サイズ未取得",new StreamDetails.SizeInfo(-1,true).label());
    }
    @Test public void estimatesHlsFromBitrateAndDuration(){
        String playlist="#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nvideo.m3u8\n";
        StreamDetails.SizeInfo info=StreamDetails.estimateManifest("HLS (m3u8)",playlist,120);
        assertTrue(info.estimated);
        assertEquals(12000000L,info.bytes);
        assertTrue(info.label().startsWith("推定 約"));
    }
    @Test public void estimatesDashFromManifestDuration(){
        String mpd="<MPD mediaPresentationDuration=\"PT1M\"><Representation bandwidth=\"1600000\" /></MPD>";
        StreamDetails.SizeInfo info=StreamDetails.estimateManifest("DASH (mpd)",mpd,0);
        assertEquals(12000000L,info.bytes);
    }
    @Test public void refusesInventedSizes(){
        StreamDetails.SizeInfo info=StreamDetails.estimateManifest("HLS (m3u8)","#EXTM3U\n#EXTINF:4,\nfile.ts\n",4);
        assertEquals(-1L,info.bytes);
        assertEquals("サイズ未取得",info.label());
    }
    @Test public void deniesUnsafePreviewTargets(){
        assertFalse(StreamDetails.safe("http://example.com/cover.jpg"));
        assertFalse(StreamDetails.safe("https://localhost/a.png"));
        assertFalse(StreamDetails.safe("https://127.0.0.1/a.png"));
        assertTrue(StreamDetails.safe("https://example.com/thumb.png"));
    }
}
