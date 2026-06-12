package com.circleguard.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Feature Toggle (configuration pattern): per-channel switches for the multi-channel notification
 * dispatcher, bound from {@code circleguard.features.channels.*}.
 *
 * <p>Every channel defaults to {@code true} so the toggle is opt-out: an operator can mute a single
 * channel by flipping a flag in the ConfigMap/env (e.g. {@code CIRCLEGUARD_FEATURES_CHANNELS_SMS=false}
 * to silence SMS during a Twilio outage) without a code change or rebuild. Consumed by
 * {@code NotificationDispatcher}.
 */
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "circleguard.features.channels")
public class NotificationFeatureProperties {
    private boolean email = true;
    private boolean sms = true;
    private boolean push = true;
}
