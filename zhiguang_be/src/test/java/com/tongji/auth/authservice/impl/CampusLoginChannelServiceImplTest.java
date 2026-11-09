package com.tongji.auth.authservice.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class CampusLoginChannelServiceImplTest {

    @Test
    void generatesPkceVerifierWithinCampusOidcMaximumLength() {
        CampusLoginChannelServiceImpl channel = new CampusLoginChannelServiceImpl(
                null, new ObjectMapper(), null, null
        );

        String verifier = ReflectionTestUtils.invokeMethod(channel, "generateCodeVerifier");

        assertThat(verifier)
                .hasSize(128)
                .matches("[A-Za-z0-9_-]+");
    }
}
