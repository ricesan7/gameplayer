package jp.sharesave.android;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.File;

public class DownloadProgressTest {
    @Test public void downloadPhaseNeverShowsFalseNinetyNinePercent(){
        DownloadProgress p=new DownloadProgress();
        DownloadProgress.State s=p.accept(99f,"[download] 99.0% of 1.00GiB");
        assertFalse(s.postProcessing);
        assertEquals(89,s.percent);
        assertTrue(s.stage.contains("99%"));
    }
    @Test public void ffmpegSizeMeansIndeterminateMergingNotNinetyNinePercent(){
        DownloadProgress p=new DownloadProgress();
        p.accept(100f,"[download] 100% of 123MiB");
        DownloadProgress.State s=p.accept(99f,"size=  100MiB time=00:03:00.00 bitrate=4523kb/s");
        assertTrue(s.postProcessing);
        assertEquals(-1,s.percent);
        assertTrue(s.stage.contains("結合"));
    }
    @Test public void progressOnlyEndsAfterMediaStore(){
        DownloadProgress p=new DownloadProgress();
        DownloadProgress.State s=p.accept(100f,"[download] 100.0% of 100MiB");
        assertEquals(-1,s.percent);
        assertFalse(s.postProcessing);
        assertTrue(s.stage.contains("完了"));
    }
}
