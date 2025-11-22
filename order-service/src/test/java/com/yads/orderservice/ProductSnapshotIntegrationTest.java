package com.yads.orderservice;

import com.yads.common.contracts.ProductEventDto;
import com.yads.orderservice.config.AmqpConfig;
import com.yads.orderservice.model.ProductSnapshot;
import com.yads.orderservice.repository.ProductSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

public class ProductSnapshotIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ProductSnapshotRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void should_create_or_update_snapshot_on_event() {
        // 1. ARRANGE
        UUID productId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        ProductEventDto event = ProductEventDto.builder()
                .productId(productId)
                .storeId(storeId)
                .name("Cyberpunk Katana")
                .price(BigDecimal.valueOf(2077.0))
                .stock(10)
                .isAvailable(true)
                .build();

        // 2. ACT
        // Store service'in attigi mesaji taklit ediyoruz.
        // Routing key kullanmadik cunku subscriber direkt queue ismine bind olmus
        // olabilir.
        // Eger exchange kullaniyorsan exchange ismini yaz. Kodda
        // queue="q.order.product.updates" demissin.
        rabbitTemplate.convertAndSend(AmqpConfig.Q_PRODUCT_UPDATES, event);

        // 3. ASSERT
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            ProductSnapshot snapshot = repository.findById(productId).orElseThrow();
            assertEquals("Cyberpunk Katana", snapshot.getName());
            assertEquals(0, snapshot.getPrice().compareTo(BigDecimal.valueOf(2077.0)));
            assertEquals(10, snapshot.getStock());
        });

        // UPDATE TESTI (Upsert mantigi calisiyor mu?)
        event.setPrice(BigDecimal.valueOf(1500.0)); // indirim geldi
        rabbitTemplate.convertAndSend(AmqpConfig.Q_PRODUCT_UPDATES, event);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            ProductSnapshot snapshot = repository.findById(productId).orElseThrow();
            assertEquals(0, snapshot.getPrice().compareTo(BigDecimal.valueOf(1500.0)));
        });
    }

    @Test
    void should_delete_snapshot_on_delete_event() {
        // 1. ARRANGE: Once urunu var edelim
        UUID productId = UUID.randomUUID();
        ProductSnapshot existing = new ProductSnapshot();
        existing.setProductId(productId);
        existing.setStoreId(UUID.randomUUID());
        existing.setName("To be deleted");
        existing.setPrice(BigDecimal.valueOf(2077.0));
        existing.setStock(10);
        existing.setAvailable(true);

        repository.save(existing);

        // 2. ACT
        // RabbitHandler silme islemi icin UUID bekliyor (DTO degil)
        rabbitTemplate.convertAndSend(AmqpConfig.Q_PRODUCT_UPDATES, productId);

        // 3. ASSERT
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertTrue(repository.findById(productId).isEmpty());
        });
    }
}