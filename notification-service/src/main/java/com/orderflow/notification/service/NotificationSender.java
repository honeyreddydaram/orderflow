package com.orderflow.notification.service;

import com.orderflow.notification.domain.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Simulates sending a notification (email/SMS) - no real provider integration in v1 (see
 * docs/architecture.md section 20), so "sending" is just a structured log line. A concrete class,
 * deliberately: this is the fault-injection seam for the crash-before-commit test
 * (@SpyBean-ing a Spring Data repository method doesn't work - see section 12 - so every service
 * from Inventory onward puts its "last write before commit" on a plain @Component like this one).
 */
@Component
public class NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(NotificationSender.class);

    public void send(Notification notification, UUID correlationId) {
        log.info("Simulated notification sent: type={} orderId={} userId={} subject=\"{}\" correlationId={}",
                notification.getType(), notification.getOrderId(), notification.getUserId(),
                notification.getSubject(), correlationId);
    }
}
