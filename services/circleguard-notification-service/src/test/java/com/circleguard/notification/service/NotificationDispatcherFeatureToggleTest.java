package com.circleguard.notification.service;

import com.circleguard.notification.config.NotificationFeatureProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Feature Toggle behaviour: a channel disabled via {@link NotificationFeatureProperties} is skipped
 * entirely by the dispatcher — neither its template is rendered nor its send invoked — while the
 * remaining channels still fire. Pure unit test (no Spring context), so it stays out of the
 * Testcontainers/integration path.
 */
class NotificationDispatcherFeatureToggleTest {

    private final EmailService emailService = mock(EmailService.class);
    private final SmsService smsService = mock(SmsService.class);
    private final PushService pushService = mock(PushService.class);
    private final TemplateService templateService = mock(TemplateService.class);

    @Test
    void disabledSmsChannelIsSkippedWhileOthersFire() {
        when(emailService.sendAsync(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(pushService.sendAsync(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(templateService.generateEmailContent(any(), any())).thenReturn("email-body");
        when(templateService.generatePushContent(any())).thenReturn("push-body");
        when(templateService.generatePushMetadata(any())).thenReturn(Map.of());

        NotificationFeatureProperties channels = new NotificationFeatureProperties();
        channels.setSms(false);

        NotificationDispatcher dispatcher =
                new NotificationDispatcher(emailService, smsService, pushService, templateService, channels);

        dispatcher.dispatch("user-1", "CONFIRMED");

        // SMS muted: neither rendered nor sent.
        verify(smsService, never()).sendAsync(any(), any());
        verify(templateService, never()).generateSmsContent(any());
        // Other channels still dispatched.
        verify(emailService).sendAsync(eq("user-1"), any());
        verify(pushService).sendAsync(eq("user-1"), any(), any());
    }
}
