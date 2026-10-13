package jp.sharesave.android;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class HlsFileOutputTest {
    @Test public void renamesRawTransportStreamFromMp4ToTs() throws Exception{
        File dir=Files.createTempDirectory("sharesave-hls-test").toFile();
        try{
            File file=new File(dir,"playlist.mp4");
            byte[] bytes=new byte[3*188+40];
            for(int i=0;i<3;i++)bytes[i*188]=(byte)0x47;
            Files.write(file.toPath(),bytes);
            assertTrue(HlsFileOutput.looksLikeMpegTs(file));
            File fixed=HlsFileOutput.correctExtension(file);
            assertEquals("playlist.ts",fixed.getName());
            assertTrue(fixed.exists());
            assertFalse(file.exists());
            assertEquals("video/mp2t",CompletedVideoFile.mime(fixed.getName()));
        }finally{
            File[] entries=dir.listFiles();
            if(entries!=null)for(File e:entries)e.delete();
            dir.delete();
        }
    }
    @Test public void doesNotRenameRealMp4() throws Exception{
        File file=File.createTempFile("video",".mp4");
        try{
            byte[] body=new byte[1024];
            body[4]='f';body[5]='t';body[6]='y';body[7]='p';
            Files.write(file.toPath(),body);
            assertFalse(HlsFileOutput.looksLikeMpegTs(file));
            assertEquals(file,HlsFileOutput.correctExtension(file));
        }finally{file.delete();}
    }
}
