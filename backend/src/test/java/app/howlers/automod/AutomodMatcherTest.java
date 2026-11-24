package app.howlers.automod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.re2j.Pattern;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AutomodMatcherTest {
    @Test
    void keywordsAreLiteralWholeWordsWithWildcards() {
        Pattern p = AutomodMatcher.keywords("buy now\ncheap*\n  \nc++");
        assertThat(AutomodMatcher.find(p, "Please BUY NOW!").matched()).isTrue();
        assertThat(AutomodMatcher.find(p, "cheapest deals").text()).isEqualTo("cheapest");
        assertThat(AutomodMatcher.find(p, "I code in c++ daily").matched()).isTrue(); // regex metacharacters are literal
        assertThat(AutomodMatcher.find(p, "buy nowhere").matched()).isFalse();
        assertThat(AutomodMatcher.find(p, "uncheap").matched()).isFalse();
        assertThat(AutomodMatcher.find(AutomodMatcher.keywords("café"), "un café noir").matched()).isTrue();
        assertThatThrownBy(() -> AutomodMatcher.keywords("***")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AutomodMatcher.keywords("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void regexesUseRe2AndRejectBacktrackingFeatures() {
        assertThat(AutomodMatcher.find(AutomodMatcher.regex("\\d{3}-\\d{4}"), "call 555-1234").text()).isEqualTo("555-1234");
        assertThatThrownBy(() -> AutomodMatcher.regex("(a)\\1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AutomodMatcher.regex("(?=a)")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AutomodMatcher.regex("x".repeat(501))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void catastrophicPatternsFinishQuickly() {
        Pattern evil = AutomodMatcher.regex("(a+)+$");
        String input = "a".repeat(19_000) + "!";
        long start = System.nanoTime();
        AutomodMatcher.Result r = AutomodMatcher.find(evil, input, Duration.ofSeconds(5));
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_000);
        assertThat(r.matched()).isFalse();
        assertThat(r.timedOut()).isFalse();
    }

    @Test
    void theTimeBudgetAbortsAMatchAndReportsIt() {
        AutomodMatcher.Result r = AutomodMatcher.find(AutomodMatcher.regex("z$"), "a".repeat(19_000), Duration.ZERO);
        assertThat(r.timedOut()).isTrue();
        assertThat(r.matched()).isFalse();
    }

    @Test
    void domainsAndHosts() {
        Set<String> blocked = AutomodMatcher.domains("https://www.Spam.example/path\nads.test");
        assertThat(blocked).containsExactly("spam.example", "ads.test");
        Set<String> hosts = AutomodMatcher.hosts("<a href=\"https://www.deals.spam.example/x\">x</a> and http://fine.example");
        assertThat(AutomodMatcher.blockedHost(hosts, blocked)).isEqualTo("deals.spam.example");
        assertThat(AutomodMatcher.blockedHost(AutomodMatcher.hosts("https://notspam.example"), blocked)).isNull();
        assertThatThrownBy(() -> AutomodMatcher.domains("not a domain")).isInstanceOf(IllegalArgumentException.class);
    }
}
