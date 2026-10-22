package com.pathongko.skein.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SkeinEndpointTest {

    @Test
    void hostAndPortDropsThePortAndUsesTheSkeinPort() {
        assertThat(SkeinEndpoint.deriveFrom("1.2.3.4:25565"))
                .isEqualTo("http://1.2.3.4:25585");
    }

    @Test
    void hostOnlyUsesTheSkeinPort() {
        assertThat(SkeinEndpoint.deriveFrom("play.example.com"))
                .isEqualTo("http://play.example.com:25585");
    }

    @Test
    void customSkeinPortIsHonoured() {
        assertThat(SkeinEndpoint.deriveFrom("play.example.com:25565", 9000))
                .isEqualTo("http://play.example.com:9000");
    }

    @Test
    void bracketedIpv6WithPortKeepsBrackets() {
        assertThat(SkeinEndpoint.deriveFrom("[fe80::1]:25565"))
                .isEqualTo("http://[fe80::1]:25585");
    }

    @Test
    void bareIpv6GetsBracketed() {
        assertThat(SkeinEndpoint.deriveFrom("::1"))
                .isEqualTo("http://[::1]:25585");
    }

    @Test
    void blankAddressIsRejected() {
        assertThatThrownBy(() -> SkeinEndpoint.deriveFrom("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
