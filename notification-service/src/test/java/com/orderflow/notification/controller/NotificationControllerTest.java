package com.orderflow.notification.controller;

import com.orderflow.notification.TestJwtFactory;
import com.orderflow.notification.config.JwtKeyConfig;
import com.orderflow.notification.config.SecurityConfig;
import com.orderflow.notification.domain.NotificationType;
import com.orderflow.notification.dto.NotificationResponse;
import com.orderflow.notification.exception.GlobalExceptionHandler;
import com.orderflow.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
@Import({SecurityConfig.class, JwtKeyConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NotificationService notificationService;

    @Test
    void list_rejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(notificationService);
    }

    @Test
    void list_returnsCallersOwnPage() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        NotificationResponse response = new NotificationResponse(UUID.randomUUID(), orderId,
                NotificationType.ORDER_CONFIRMED, "Your order has been confirmed",
                "Order " + orderId + " has been confirmed. Total: 29.97.", Instant.now());
        Page<NotificationResponse> page = new PageImpl<>(List.of(response));
        when(notificationService.listForUser(eq(userId), any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer " + TestJwtFactory.customerToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].orderId").value(orderId.toString()));
    }
}
