package jp.sharesave.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class BrowserUrlRulesTest {
    @Test public void acceptsHttpsPages(){
        assertTrue(BrowserUrlRules.valid("https://example.com/video?id=123"));
        assertTrue(BrowserUrlRules.valid("https://example.org:443/path?q=1"));
    }
    @Test public void rejectsUnsafeOrMalformedUrls(){
        assertFalse(BrowserUrlRules.valid(null));
        assertFalse(BrowserUrlRules.valid("javascript:alert(1)"));
        assertFalse(BrowserUrlRules.valid("http://example.com"));
        assertFalse(BrowserUrlRules.valid("https://user:secret@example.org/"));
        assertFalse(BrowserUrlRules.valid("https://localhost/"));
        assertFalse(BrowserUrlRules.valid("https://example.internal/page"));
        assertFalse(BrowserUrlRules.valid("https://invalid url/"));
    }
    @Test public void providesFallbackTitleAndTruncatesLongTitles(){
        assertEquals("example.org",BrowserUrlRules.title("","https://example.org/a"));
        assertEquals("Example  Page",BrowserUrlRules.title(" Example\n Page ","https://example.org/"));
        assertEquals(100,BrowserUrlRules.title("a".repeat(150),"https://example.org").length());
    }
}
