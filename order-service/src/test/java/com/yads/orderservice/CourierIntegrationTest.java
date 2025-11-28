package com.yads.orderservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yads.common.contracts.CourierAssignedContract;
import com.yads.common.contracts.CourierAssignmentFailedContract;
import com.yads.common.contracts.OrderAssignedContract;
import com.yads.common.contracts.OrderCancelledContract;
import com.yads.orderservice.config.AmqpConfig;
import com.yads.orderservice.model.Order;
import com.yads.orderservice.model.OrderItem;
import com.yads.orderservice.model.OrderStatus;
import com.yads.orderservice.repository.OrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

public class CourierIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private BlockingQueue<Object> capturedMessages;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    @AfterEach
    void cleanup() {
        orderRepository.deleteAll();
        capturedMessages.clear();
    }

    // --- TEST SPY CONFIGURATION ---
    // Bu config outbound mesajlari (notification service'e gidenleri) yakalar
    @TestConfiguration
    static class TestRabbitConfig {
        @Bean
        public BlockingQueue<Object> capturedMessages() {
            return new LinkedBlockingQueue<>();
        }

        @Bean
        public Queue courierSpyQueue() {
            return new Queue("courier.spy.queue", false);
        }

        @Bean
        public Binding bindingAssigned(Queue courierSpyQueue) {
            return BindingBuilder.bind(courierSpyQueue)
                    .to(new DirectExchange("order_events_exchange"))
                    .with("order.assigned");
        }

        @Bean
        public Binding bindingCancelled(Queue courierSpyQueue) {
            return BindingBuilder.bind(courierSpyQueue)
                    .to(new DirectExchange("order_events_exchange"))
                    .with("order.cancelled");
        }

        @RabbitListener(queues = "courier.spy.queue")
        public void spyListener(Message message) {
            capturedMessages().offer(message);
        }
    }

    // --- HAPPY PATH: COURIER ASSIGNED ---
    @Test
    void should_assign_courier_and_publish_event() {
        // 1. ARRANGE: Order PREPARING statüsünde olmali

        UUID courierId = UUID.randomUUID();

        Order order = new Order();
        order.setStoreId(UUID.randomUUID());
        order.setUserId(UUID.randomUUID());
        order.setStatus(OrderStatus.PREPARING); // Kritik durum
        order.setTotalPrice(BigDecimal.TEN);

        Order savedOrder = orderRepository.save(order);
        UUID orderId = savedOrder.getId();

        CourierAssignedContract contract = CourierAssignedContract.builder()
                .orderId(orderId)
                .courierId(courierId)
                .build();

        // 2. ACT: Courier Service'den mesaj gelmis gibi yapiyoruz
        rabbitTemplate.convertAndSend(AmqpConfig.Q_COURIER_ASSIGNED, contract);

        // 3. ASSERT: DB Update
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Order updated = orderRepository.findById(orderId).orElseThrow();
            assertEquals(courierId, updated.getCourierId());
            assertEquals(OrderStatus.PREPARING, updated.getStatus()); // Statu degismiyor, sadece kurye ataniyor
        });

        // 4. ASSERT: Outbound Event (Notification Service icin)
        try {
            Object captured = capturedMessages.poll(5, TimeUnit.SECONDS);
            assertNotNull(captured, "message should exist");

            // cast to Message first
            org.springframework.amqp.core.Message message = (org.springframework.amqp.core.Message) captured;

            // deserialize the bytes manually
            OrderAssignedContract event = objectMapper.readValue(message.getBody(), OrderAssignedContract.class);

            // now you can assert on the actual DTO
            assertEquals(courierId, event.getCourierId());

        } catch (InterruptedException | IOException e) {
            fail("failed to process message: " + e.getMessage());
        }
    }

    // --- NEGATIVE PATH: IDEMPOTENCY & STATE CHECK ---
    @Test
    void should_ignore_assignment_if_order_cancelled() throws InterruptedException {
        Order order = new Order();
        // constraints don't care about your test context. feed the beast.
        order.setStoreId(UUID.randomUUID());
        order.setUserId(UUID.randomUUID());
        order.setTotalPrice(BigDecimal.TEN); // probably needed too
        order.setStatus(OrderStatus.CANCELLED);

        Order savedOrder = orderRepository.save(order);
        UUID orderId = savedOrder.getId();

        CourierAssignedContract contract = CourierAssignedContract.builder()
                .orderId(orderId)
                .courierId(UUID.randomUUID())
                .build();

        // 2. ACT
        rabbitTemplate.convertAndSend(AmqpConfig.Q_COURIER_ASSIGNED, contract);

        // 3. ASSERT
        // Async oldugu icin "hicbir sey olmadigini" kanitlamak zordur.
        // Biraz bekleyip hala courierId null mu diye bakariz.
        TimeUnit.SECONDS.sleep(2);

        Order ignoredOrder = orderRepository.findById(orderId).orElseThrow();
        assertNull(ignoredOrder.getCourierId(), "Courier should NOT be assigned to a CANCELLED order");
    }

    // --- CRITICAL PATH: ASSIGNMENT FAILED & STOCK RESTORATION ---
    @Test
    void should_cancel_order_and_restore_stock_when_assignment_fails() {
        // 1. ARRANGE
        Order order = new Order();
        order.setStoreId(UUID.randomUUID());
        order.setUserId(UUID.randomUUID());
        order.setStatus(OrderStatus.PREPARING);
        order.setTotalPrice(BigDecimal.TEN); // <--- FIX 1: feed the db constraints

        OrderItem item = new OrderItem();
        item.setProductId(UUID.randomUUID());
        item.setProductName("Labubu");
        item.setQuantity(5);
        item.setPrice(BigDecimal.TEN);
        item.setOrder(order);
        order.setItems(List.of(item));

        Order savedOrder = orderRepository.save(order);
        UUID orderId = savedOrder.getId();

        CourierAssignmentFailedContract contract = CourierAssignmentFailedContract.builder()
                .orderId(orderId)
                .userId(order.getUserId())
                .reason("No couriers available nearby")
                .build();

        // 2. ACT
        rabbitTemplate.convertAndSend(AmqpConfig.Q_COURIER_ASSIGNMENT_FAILED, contract);

        // 3. ASSERT: DB Cancelled
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Order updated = orderRepository.findById(orderId).orElseThrow();
            assertEquals(OrderStatus.CANCELLED, updated.getStatus());
        });

        // 4. ASSERT: Outbound Event
        try {
            Object captured = capturedMessages.poll(5, TimeUnit.SECONDS);
            assertNotNull(captured, "Cancellation event should be published");

            // <--- FIX 2: unwrap the envelope manually like before
            org.springframework.amqp.core.Message message = (org.springframework.amqp.core.Message) captured;
            OrderCancelledContract event = objectMapper.readValue(message.getBody(), OrderCancelledContract.class);

            assertEquals("PREPARING", event.getOldStatus());
            assertFalse(event.getItems().isEmpty());
            assertEquals(5, event.getItems().get(0).getQuantity());

        } catch (InterruptedException | IOException e) {
            fail("Failed to process message: " + e.getMessage());
        }
    }
}