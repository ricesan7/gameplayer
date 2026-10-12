package jp.sharesave.android;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class SessionCookiesTest {
    @Test public void writesOnlySiteScopedCookieRows() throws Exception {
        File temp=File.createTempFile("sharesave-cookies",".txt");
        try {
            int count=SessionCookies.writeCookieJar(temp,
                "https://stream.example.com/movie/master.m3u8",
                "signed=tokenA; pref=light",
                "https://player.example.net/embed",
                "session=tokenB");
            String contents=new String(Files.readAllBytes(temp.toPath()),StandardCharsets.UTF_8);
            assertEquals(3,count);
            assertTrue(contents.startsWith("# Netscape HTTP Cookie File"));
            assertTrue(contents.contains("stream.example.com\tFALSE\t/\tTRUE\t0\tsigned\ttokenA"));
            assertTrue(contents.contains("player.example.net\tFALSE\t/\tTRUE\t0\tsession\ttokenB"));
            assertFalse(contents.contains(".example.com\tTRUE"));
        } finally {temp.delete();}
    }
    @Test public void doesNotWriteMalformedCookies() throws Exception {
        File temp=File.createTempFile("sharesave-cookies",".txt");
        try {
            int count=SessionCookies.writeCookieJar(temp,
                "https://cdn.example.com/v.m3u8","bad cookie=yes; valid=value",
                "http://nonhttps.example.com","leak=no");
            assertEquals(1,count);
            String content=new String(Files.readAllBytes(temp.toPath()),StandardCharsets.UTF_8);
            assertTrue(content.contains("valid\tvalue"));
            assertFalse(content.contains("leak"));
        }finally{temp.delete();}
    }
}
