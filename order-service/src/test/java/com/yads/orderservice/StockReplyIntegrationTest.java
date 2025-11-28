package com.yads.orderservice;

import com.yads.common.contracts.StockReservationFailedContract;
import com.yads.common.contracts.StockReservedContract;
import com.yads.common.contracts.OrderAssignmentContract;
import com.yads.common.contracts.OrderCancelledContract;
import com.yads.common.model.Address;
import com.yads.orderservice.config.AmqpConfig;
import com.yads.orderservice.model.Order;
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

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

public class StockReplyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private OrderRepository orderRepository;

    // Casus queue'ya düşen mesajları burada toplayacağız
    @Autowired
    private BlockingQueue<Object> capturedMessages;

    // ARRANGE: Temizlik sart
    @BeforeEach
    @AfterEach
    void clear() {
        orderRepository.deleteAll();
        capturedMessages.clear();
    }

    // --- TEST CONFIGURATION (CASUS) ---
    @TestConfiguration
    static class TestRabbitConfig {

        // Mesajlari thread-safe bir kuyrukta tutalim ki test thread'i okuyabilsin
        @Bean
        public BlockingQueue<Object> capturedMessages() {
            return new LinkedBlockingQueue<>();
        }

        // Senin kodunun mesaj attigi exchange bu.
        // Buna baglanacak gecici bir queue olusturuyoruz.
        @Bean
        public Queue testSpyQueue() {
            return new Queue("test.spy.queue", false);
        }

        @Bean
        public Binding bindingPreparing(Queue testSpyQueue) {
            // order.preparing routing key'ini dinliyoruz
            return BindingBuilder.bind(testSpyQueue)
                    .to(new DirectExchange("order_events_exchange"))
                    .with("order.preparing");
        }

        @Bean
        public Binding bindingCancelled(Queue testSpyQueue) {
            // order.cancelled routing key'ini dinliyoruz
            return BindingBuilder.bind(testSpyQueue)
                    .to(new DirectExchange("order_events_exchange"))
                    .with("order.cancelled");
        }

        // Casus Listener
        @RabbitListener(queues = "test.spy.queue")
        public void spyListener(Message message) {
            // capture the raw message, headers and all
            capturedMessages().offer(message);
        }
    }

    // --- TESTLER ---

    @Test
    void should_handle_stock_reserved_event_correctly() {
        // 1. ARRANGE
        Order order = new Order();
        order.setStoreId(UUID.randomUUID());
        order.setUserId(UUID.randomUUID());
        order.setStatus(OrderStatus.RESERVING_STOCK);
        order.setTotalPrice(java.math.BigDecimal.TEN);

        // capture the saved instance to get the generated ID
        order = orderRepository.save(order);
        UUID orderId = order.getId();

        Address pickupAddress = new Address();
        pickupAddress.setCity("Gotham");
        pickupAddress.setStreet("Crime Alley");

        StockReservedContract contract = StockReservedContract.builder()
                .orderId(orderId) // use the real ID
                .storeId(order.getStoreId())
                .userId(order.getUserId())
                .pickupAddress(pickupAddress)
                .build();

        // 2. ACT
        // Store service gibi davranıp senin kuyruğuna mesaj atıyoruz
        rabbitTemplate.convertAndSend(AmqpConfig.Q_STOCK_RESERVED, contract);

        // 3. ASSERT (DB Degisikligi)
        // Async olduğu için anında değişmez, bekliyoruz.
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Order updated = orderRepository.findById(orderId).orElseThrow();
            assertEquals(OrderStatus.PREPARING, updated.getStatus());
            assertNotNull(updated.getPickupAddress());
            assertEquals("Gotham", updated.getPickupAddress().getCity());
        });

        // 4. ASSERT (Outbound Event)
        // Subscriber bir sonraki adim icin "order.preparing" firlatti mi?
        try {
            Object captured = capturedMessages.poll(5, TimeUnit.SECONDS);
            assertNotNull(captured, "Courier assignment event should have been published");

            // manual conversion using the template that knows how to ser/de
            Message message = (Message) captured;
            Object payload = rabbitTemplate.getMessageConverter().fromMessage(message);

            // now this should work
            assertInstanceOf(OrderAssignmentContract.class, payload,
                    "Expected OrderAssignmentContract but got " + payload.getClass().getName());

            OrderAssignmentContract event = (OrderAssignmentContract) payload;
            assertEquals(orderId, event.getOrderId());

        } catch (InterruptedException e) {
            fail("Interrupted while waiting for message");
        }
    }

    @Test
    void should_cancel_order_when_stock_reservation_fails() {
        // 1. ARRANGE
        Order order = new Order();
        order.setStoreId(UUID.randomUUID());
        order.setUserId(UUID.randomUUID());
        order.setStatus(OrderStatus.RESERVING_STOCK); // Beklenen durum

        order.setTotalPrice(java.math.BigDecimal.TEN);

        // SAVE FIRST, THEN GET ID
        order = orderRepository.save(order);
        UUID realOrderId = order.getId(); // <--- THIS is the actual ID

        StockReservationFailedContract contract = StockReservationFailedContract.builder()
                .orderId(realOrderId) // use the real ID
                .userId(order.getUserId())
                .reason("Out of stock bro")
                .build();

        // 2. ACT
        rabbitTemplate.convertAndSend(AmqpConfig.Q_STOCK_RESERVATION_FAILED, contract);

        // 3. ASSERT (DB)
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            // use the real ID to look it up
            Order updated = orderRepository.findById(realOrderId).orElseThrow();
            assertEquals(OrderStatus.CANCELLED, updated.getStatus());
        });

        // 4. ASSERT (Notification Event)
        try {
            Object captured = capturedMessages.poll(5, TimeUnit.SECONDS);
            assertNotNull(captured, "should have published cancellation event");

            // manual deserialize bc spring is stubborn
            Message message = (Message) captured;
            Object payload = rabbitTemplate.getMessageConverter().fromMessage(message);

            assertInstanceOf(OrderCancelledContract.class, payload,
                    "expected OrderCancelledContract but got " + payload.getClass().getName());

            OrderCancelledContract event = (OrderCancelledContract) payload;
            assertEquals("RESERVING_STOCK", event.getOldStatus());

        } catch (InterruptedException e) {
            fail("interrupted");
        }
    }
}