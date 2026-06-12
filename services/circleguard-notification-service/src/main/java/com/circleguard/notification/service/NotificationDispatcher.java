package com.circleguard.notification.service;

import com.circleguard.notification.config.NotificationFeatureProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationDispatcher {

    private final EmailService emailService;
    private final SmsService smsService;
    private final PushService pushService;
    private final TemplateService templateService;
    // Feature Toggle: per-channel switches (default ON). A disabled channel is skipped entirely —
    // we don't even render its template — so it costs nothing when muted.
    private final NotificationFeatureProperties channels;

    public void dispatch(String userId, String status) {
        log.info("Dispatching contextual multi-channel notifications for user: {} with status: {}", userId, status);

        List<CompletableFuture<Void>> tasks = new ArrayList<>();

        if (channels.isEmail()) {
            tasks.add(emailService.sendAsync(userId, templateService.generateEmailContent(status, userId)));
        } else {
            log.debug("[feature-toggle] email channel disabled; skipping for user {}", userId);
        }

        if (channels.isSms()) {
            tasks.add(smsService.sendAsync(userId, templateService.generateSmsContent(status)));
        } else {
            log.debug("[feature-toggle] sms channel disabled; skipping for user {}", userId);
        }

        if (channels.isPush()) {
            String pushContent = templateService.generatePushContent(status);
            Map<String, String> pushMetadata = templateService.generatePushMetadata(status);
            tasks.add(pushService.sendAsync(userId, pushContent, pushMetadata));
        } else {
            log.debug("[feature-toggle] push channel disabled; skipping for user {}", userId);
        }

        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).handle((result, ex) -> {
            if (ex != null) {
                log.error("Error during multi-channel dispatch for user {}: {}", userId, ex.getMessage());
            } else {
                log.info("Multi-channel dispatch completed successfully for user: {}", userId);
            }
            return result;
        });
    }
}
