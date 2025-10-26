package com.yads.orderservice.service;

import com.yads.common.contracts.ProductEventDto;
import com.yads.orderservice.model.ProductSnapshot;
import com.yads.orderservice.repository.ProductSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@RabbitListener(queues = "q.order_service.product_updates")
public class StoreEventSubscriber {

    private final ProductSnapshotRepository snapshotRepository;

    // "upsert" (update or insert) logic
    @RabbitHandler
    public void handleProductUpdate(ProductEventDto eventDto) {
        try {
            ProductSnapshot snapshot = snapshotRepository.findById(eventDto.getProductId())
                    .orElse(new ProductSnapshot()); // yoksa yeni oluştur

            snapshot.setProductId(eventDto.getProductId());
            snapshot.setStoreId(eventDto.getStoreId());
            snapshot.setName(eventDto.getName());
            snapshot.setPrice(eventDto.getPrice());
            snapshot.setStock(eventDto.getStock());
            snapshot.setAvailable(eventDto.isAvailable());

            snapshotRepository.save(snapshot);
            log.info("Product snapshot updated: {}", eventDto.getProductId());
        } catch (Exception e) {
            log.error("Failed to process product update event: {}", e.getMessage());
        }
    }

    // "delete" logic
    @RabbitHandler
    public void handleProductDelete(UUID productId) {
        try {
            snapshotRepository.deleteById(productId);
            log.info("Product snapshot deleted: {}", productId);
        } catch (Exception e) {
            log.error("Failed to process product delete event: {}", e.getMessage());
        }
    }

    // not: RabbitMQ will automatically route DTO and UUID messages to the appropriate listener
}