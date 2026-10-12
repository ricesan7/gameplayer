package jp.sharesave.android;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class CompletedVideoFileTest {
    @Test public void prefersLargeCompletedVideoNotAudioSidecar() throws Exception {
        File dir=Files.createTempDirectory("sharesave-videos").toFile();
        try{
            File audio=new File(dir,"movie.f140.m4a");
            File fragment=new File(dir,"movie.f137.mp4.part");
            File merged=new File(dir,"movie.mp4");
            Files.write(audio.toPath(),new byte[200]);
            Files.write(fragment.toPath(),new byte[400]);
            Files.write(merged.toPath(),new byte[100]);
            assertEquals(merged,CompletedVideoFile.choose(dir.listFiles()));
            assertEquals("video/mp4",CompletedVideoFile.mime(merged.getName()));
        }finally{
            File[] files=dir.listFiles();
            if(files!=null)for(File f:files)f.delete();
            dir.delete();
        }
    }
    @Test public void noVideoMeansNoCompletedDownload(){
        assertNull(CompletedVideoFile.choose(new File[0]));
        assertNull(CompletedVideoFile.mime("voice.m4a"));
        assertEquals("video/webm",CompletedVideoFile.mime("movie.webm"));
    }
}
