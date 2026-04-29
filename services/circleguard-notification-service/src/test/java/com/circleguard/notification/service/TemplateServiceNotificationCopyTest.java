package com.circleguard.notification.service;

import freemarker.template.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for user-visible notification copy independent of Freemarker rendering.
 */
@ExtendWith(MockitoExtension.class)
class TemplateServiceNotificationCopyTest {

    @Mock
    private Configuration freemarkerConfig;

    private TemplateService templateService;

    @BeforeEach
    void setUp() {
        templateService = new TemplateService(freemarkerConfig);
        ReflectionTestUtils.setField(templateService, "testingUrl", "https://example.com/testing");
        ReflectionTestUtils.setField(templateService, "isolationUrl", "https://example.com/isolation");
        ReflectionTestUtils.setField(templateService, "guidelinesDeepLink", "circleguard://guidelines");
    }

    @Test
    void generatePushContent_forSuspect_containsKeywordSuspect() {
        assertTrue(templateService.generatePushContent("SUSPECT").contains("SUSPECT"));
    }

    @Test
    void generateSmsContent_includesStatusKeyword() {
        assertTrue(templateService.generateSmsContent("CONFIRMED").contains("CONFIRMED"));
    }

    @Test
    void generatePushMetadata_forProbable_includesGuidelinesDeepLink() {
        assertEquals("circleguard://guidelines", templateService.generatePushMetadata("PROBABLE").get("url"));
    }

    @Test
    void generatePushMetadata_forRecovered_returnsEmptyMap() {
        assertTrue(templateService.generatePushMetadata("RECOVERED").isEmpty());
    }

    @Test
    void generatePushContent_forUnknownStatus_appendsStatusText() {
        String body = templateService.generatePushContent("CUSTOM");
        assertTrue(body.contains("CUSTOM"));
    }
}
