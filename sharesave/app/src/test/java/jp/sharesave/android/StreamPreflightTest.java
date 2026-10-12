package jp.sharesave.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class StreamPreflightTest {
    @Test public void limitsHttpPreflightToAdaptiveStreamingManifests(){
        assertTrue(StreamPreflight.isManifest("https://cdn.example.org/movie/master.m3u8?expires=123"));
        assertTrue(StreamPreflight.isManifest("https://cdn.example.org/movie/manifest.mpd"));
        assertFalse(StreamPreflight.isManifest("https://cdn.example.org/movie.mp4"));
        assertFalse(StreamPreflight.isManifest("https://cdn.example.org/segment.ts"));
        assertFalse(StreamPreflight.isManifest("blob:https://cdn.example.org/video"));
    }
}
