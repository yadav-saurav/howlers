package app.howlers.auth.device;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class UserAgentFamilyTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0 | Firefox | Windows",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 | Chrome | macOS",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0 | Edge | Windows",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1 | Safari | iOS",
            "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/26.0 Chrome/122.0.0.0 Mobile Safari/537.36 | Samsung Internet | Android",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 OPR/115.0.0.0 | Opera | Linux",
            "Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 | Chrome | ChromeOS",
            "curl/8.5.0 | curl | Unknown OS"})
    void familiesNotVersions(String ua, String browser, String os) {
        UserAgentFamily f = UserAgentFamily.parse(ua);
        assertThat(f.browser()).isEqualTo(browser);
        assertThat(f.os()).isEqualTo(os);
    }

    @Test
    void missingHeaders() {
        assertThat(UserAgentFamily.parse(null).describe()).isEqualTo("Unknown browser on Unknown OS");
        assertThat(UserAgentFamily.parse("").browser()).isEqualTo(UserAgentFamily.UNKNOWN_BROWSER);
    }

    @Test
    void networkPrefixesAreCoarseAndNeverResolveNames() {
        assertThat(KnownDeviceService.networkPrefix("203.0.113.77")).isEqualTo("203.0.113.0/24");
        assertThat(KnownDeviceService.networkPrefix("2001:db8:abcd:12::1")).isEqualTo("2001:0db8:abcd::/48");
        assertThat(KnownDeviceService.networkPrefix("example.com")).isNull();
        assertThat(KnownDeviceService.networkPrefix(null)).isNull();
    }
}
