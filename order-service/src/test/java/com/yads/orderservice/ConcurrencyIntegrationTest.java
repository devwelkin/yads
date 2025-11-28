package com.yads.orderservice;

import com.yads.orderservice.model.Order;
import com.yads.orderservice.model.OrderStatus;
import com.yads.orderservice.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class ConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void should_prevent_lost_updates_using_optimistic_locking() {
        // 1. ARRANGE: Initial Order Setup
        // Veritabanına ilk versiyonu (v0) koyuyoruz.
        Order order = new Order();
        order.setUserId(UUID.randomUUID());
        order.setStoreId(UUID.randomUUID());
        order.setStatus(OrderStatus.PENDING);
        order.setTotalPrice(BigDecimal.TEN);

        Order savedOrder = orderRepository.save(order);
        UUID orderId = savedOrder.getId();

        // 2. ACT: Simulate Concurrent Access
        // Kullanıcı A veriyi okuyor (Versiyon 0)
        Order user1View = orderRepository.findById(orderId).orElseThrow();

        // Kullanıcı B veriyi okuyor (Versiyon 0) - A henüz commit etmedi!
        Order user2View = orderRepository.findById(orderId).orElseThrow();

        assertNotNull(user1View.getVersion());
        assertEquals(user1View.getVersion(), user2View.getVersion(), "Versions should match initially");

        // 3. First Update Wins
        // Kullanıcı A işlemi yapıyor ve kaydediyor.
        user1View.setStatus(OrderStatus.RESERVING_STOCK);
        orderRepository.saveAndFlush(user1View); // DB'de version 1 oldu

        // 4. Second Update Fails (Stale Data)
        // Kullanıcı B, elindeki "Versiyon 0" olan objeyi değiştirmeye çalışıyor.
        // Ama DB'de versiyon 1 var. Hibernate bunu yemez.
        user2View.setStatus(OrderStatus.CANCELLED);

        // 5. ASSERT
        assertThrows(ObjectOptimisticLockingFailureException.class, () -> {
            // Burasi patlamali. Patlamazsa son yazan kazanır (Last Write Wins) ve A'nın
            // verisi ezilir.
            // Bu da "Lost Update" problemidir.
            orderRepository.saveAndFlush(user2View);
        });

        // 6. VERIFY STATE
        // Veritabanında kazanan User A olmalı.
        Order finalState = orderRepository.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.RESERVING_STOCK, finalState.getStatus());
        assertNotEquals(OrderStatus.CANCELLED, finalState.getStatus());
    }
}