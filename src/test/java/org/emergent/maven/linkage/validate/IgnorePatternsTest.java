package org.emergent.maven.linkage.validate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class IgnorePatternsTest {

    @Test
    void starMatchesAnyRunIncludingDots() {
        IgnorePatterns patterns = IgnorePatterns.compile(List.of("javax.annotation.*"));

        assertThat(patterns.isIgnored("javax.annotation.Nullable")).isTrue();
        assertThat(patterns.isIgnored("javax.annotation.meta.When")).isTrue();
        assertThat(patterns.isIgnored("javax.annotationx.Foo")).isFalse();
        assertThat(patterns.isIgnored("javax.annotation")).isFalse();
    }

    @Test
    void questionMarkMatchesSingleCharacter() {
        IgnorePatterns patterns = IgnorePatterns.compile(List.of("com.example.Foo?"));

        assertThat(patterns.isIgnored("com.example.Foo1")).isTrue();
        assertThat(patterns.isIgnored("com.example.Foo12")).isFalse();
        assertThat(patterns.isIgnored("com.example.Foo")).isFalse();
    }

    @Test
    void noneIgnoresNothing() {
        assertThat(IgnorePatterns.NONE.isIgnored("anything.at.All")).isFalse();
    }
}
