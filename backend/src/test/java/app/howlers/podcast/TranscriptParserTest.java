package app.howlers.podcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.howlers.common.error.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;

class TranscriptParserTest {

    @Test
    void parsesWebVttWithVoicesAndSettings() {
        String vtt = """
                WEBVTT

                1
                00:00:00.000 --> 00:00:04.500 align:start
                <v Host A>Welcome to the show &amp; thanks for listening.

                00:00:04.500 --> 00:01:02.250
                Host B: Today we dig into Rust
                versus Go.
                """;
        List<TranscriptParser.Segment> s = TranscriptParser.parse("vtt", vtt, null);
        assertThat(s).hasSize(2);
        assertThat(s.get(0).speaker()).isEqualTo("Host A");
        assertThat(s.get(0).text()).isEqualTo("Welcome to the show & thanks for listening.");
        assertThat(s.get(1).start()).isEqualTo(4.5);
        assertThat(s.get(1).end()).isEqualTo(62.25);
        assertThat(s.get(1).speaker()).isEqualTo("Host B");
        assertThat(s.get(1).text()).isEqualTo("Today we dig into Rust versus Go.");
    }

    @Test
    void parsesSrtAndTimestampedText() {
        String srt = "1\r\n00:00:01,000 --> 00:00:03,000\r\nHello there\r\n\r\n2\r\n01:00:00,000 --> 01:00:02,500\r\nOne hour in\r\n";
        var s = TranscriptParser.parse("srt", srt, null);
        assertThat(s).extracting(TranscriptParser.Segment::start).containsExactly(1.0, 3600.0);

        var t = TranscriptParser.parse("timestamped", "[00:00] Host A: Intro\n[1:30] Host B: Main point\ncontinues here\n", 200);
        assertThat(t).hasSize(2);
        assertThat(t.get(0).end()).isEqualTo(90.0);       // next segment's start
        assertThat(t.get(1).end()).isEqualTo(200.0);      // episode duration
        assertThat(t.get(1).text()).isEqualTo("Main point continues here");
    }

    @Test
    void rejectsUntimedOrMalformedInput() {
        assertThatThrownBy(() -> TranscriptParser.parse("vtt", "just some words", null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> TranscriptParser.parse("timestamped", "[1:00] b\n[0:30] a", null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> TranscriptParser.parse("docx", "x", null)).isInstanceOf(ApiException.class);
    }

    @Test
    void roundTripsToVtt() {
        var vtt = TranscriptParser.toVtt(List.of(new TranscriptParser.Segment(3661.5, 3662, "A <b>", "x --> y & z")));
        assertThat(vtt).startsWith("WEBVTT").contains("01:01:01.500 --> 01:01:02.000").contains("<v A &lt;b&gt;>x → y &amp; z");
    }

    @Test
    void sniffsMediaByMagicBytes() {
        assertThat(MediaSniffer.sniff("ID3\u0004\0\0\0\0\0\0\0\0\0\0\0\0".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)).extension()).isEqualTo("mp3");
        assertThat(MediaSniffer.sniff(bytes(0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'M', '4', 'A', ' ')).mediaKind()).isEqualTo("AUDIO");
        assertThat(MediaSniffer.sniff(bytes(0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm')).mediaKind()).isEqualTo("VIDEO");
        assertThatThrownBy(() -> MediaSniffer.sniff("%PDF-1.7 not media".getBytes())).isInstanceOf(ApiException.class);
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }
}
