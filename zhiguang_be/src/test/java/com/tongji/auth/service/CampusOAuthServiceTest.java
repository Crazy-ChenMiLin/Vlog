package com.tongji.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class CampusOAuthServiceTest {

    @Test
    void generatesPkceVerifierWithinCampusOidcMaximumLength() {
        CampusOAuthService service = new CampusOAuthService(
                null, null, null, null, new ObjectMapper(), null, null
        );

        String verifier = ReflectionTestUtils.invokeMethod(service, "generateCodeVerifier");

        assertThat(verifier)
                .hasSize(128)
                .matches("[A-Za-z0-9_-]+");
    }
}
