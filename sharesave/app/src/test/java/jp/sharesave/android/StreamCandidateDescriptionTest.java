package jp.sharesave.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class StreamCandidateDescriptionTest {
    @Test public void differentiatesOtherwiseIdenticalCandidatesWithoutShowingTokens(){
        String a="https://cdn.example.com/hls/master.m3u8?token=alpha";
        String b="https://cdn.example.com/hls/master.m3u8?token=bravo";
        assertNotEquals(StreamCandidateDescription.fingerprint(a),
                        StreamCandidateDescription.fingerprint(b));
        assertEquals("cdn.example.com / master.m3u8",
                     StreamCandidateDescription.source(a));
        assertFalse(StreamCandidateDescription.source(a).contains("alpha"));
    }
    @Test public void distinguishesKnownAndInferredQuality(){
        assertEquals("1920×1080（プレイヤー検出）",
                     StreamCandidateDescription.quality("https://cdn.example.com/master.m3u8",1920,1080));
        assertEquals("推定 1080p",StreamCandidateDescription.quality("https://cdn.example.com/1080p/stream.m3u8",0,0));
        assertEquals("画質未取得",StreamCandidateDescription.quality("https://cdn.example.com/video.m3u8",0,0));
    }
    @Test public void choosesThreeDistinctPositionsWhenDurationKnown(){
        long[] positions=StreamCandidateDescription.sampleTimes(200.0);
        assertEquals(3,positions.length);
        assertTrue(positions[0]<positions[1]);
        assertTrue(positions[1]<positions[2]);
        assertTrue(positions[2]<200000);
        assertEquals("00:24",StreamCandidateDescription.timeLabel(positions[0]));
    }
    @Test public void shortVideosRemainInRange(){
        long[] positions=StreamCandidateDescription.sampleTimes(1.5);
        for(long p:positions){assertTrue(p>=0);assertTrue(p<=1100);}
    }
}
