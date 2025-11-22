package com.yads.storeservice.subscriber;

import com.yads.common.contracts.StockReservationFailedContract;
import com.yads.common.contracts.StockReservationRequestContract;
import com.yads.common.contracts.StockReservedContract;
import com.yads.common.dto.BatchReserveStockRequest;
import com.yads.storeservice.config.AmqpConfig;
import com.yads.storeservice.model.IdempotentEvent;
import com.yads.storeservice.model.OutboxEvent;
import com.yads.storeservice.model.Store;
import com.yads.storeservice.repository.IdempotentEventRepository;
import com.yads.storeservice.repository.OutboxRepository;
import com.yads.storeservice.repository.StoreRepository;
import com.yads.storeservice.services.ProductService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Subscriber that participates in the stock reservation saga.
 * Receives requests from order-service, attempts stock reservation,
 * fetches store's pickup address, and publishes success/failure events.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StockReservationSubscriber {

        private final ProductService productService;
        private final StoreRepository storeRepository;
        private final OutboxRepository outboxRepository;
        private final ObjectMapper objectMapper;
        private final IdempotentEventRepository idempotentEventRepository;

        @Autowired
        @Lazy
        private StockReservationSubscriber self;

        /**
         * Handles stock reservation requests from order-service.
         * Attempts to reserve stock, fetches store's pickup address from DB,
         * and publishes either success or failure event.
         */
        @Transactional
        @RabbitListener(queues = AmqpConfig.Q_STOCK_RESERVE)
        public void handleStockReservationRequest(StockReservationRequestContract contract) {
                log.info("Received 'order.stock_reservation.requested' event. orderId={}, storeId={}",
                                contract.getOrderId(), contract.getStoreId());

                // Idempotency Check (First Writer Wins) - MUST be in separate transaction
                // so it persists even if stock reservation fails and main transaction rolls
                // back
                String eventKey = "RESERVE_STOCK:" + contract.getOrderId();
                if (!self.tryCreateIdempotencyKey(eventKey)) {
                        log.warn("Event already processed (idempotency check). Skipping. key={}", eventKey);
                        return;
                }

                BatchReserveStockRequest reserveRequest = BatchReserveStockRequest.builder()
                                .storeId(contract.getStoreId())
                                .items(contract.getItems())
                                .build();

                try {
                        // Attempt stock reservation (this method is @Transactional)
                        productService.batchReserveStock(reserveRequest);

                        // SUCCESS: Stock reserved, transaction will commit
                        log.info("Stock reservation SUCCESS. orderId={}", contract.getOrderId());

                        // Fetch store's pickup address from our own database
                        Store store = storeRepository.findById(contract.getStoreId())
                                        .orElseThrow(() -> new RuntimeException(
                                                        "Store not found: " + contract.getStoreId()));

                        StockReservedContract replyContract = StockReservedContract.builder()
                                        .orderId(contract.getOrderId())
                                        .storeId(contract.getStoreId())
                                        .userId(contract.getUserId())
                                        .pickupAddress(store.getAddress()) // From store-service's own DB
                                        .shippingAddress(contract.getShippingAddress())
                                        .build();

                        String payload = objectMapper.writeValueAsString(replyContract);

                        OutboxEvent outboxEvent = OutboxEvent.builder()
                                        .aggregateType("ORDER")
                                        .aggregateId(contract.getOrderId().toString())
                                        .type("order.stock_reserved")
                                        .payload(payload)
                                        .createdAt(LocalDateTime.now())
                                        .processed(false)
                                        .build();

                        outboxRepository.save(outboxEvent);
                        log.info("Saved 'order.stock_reserved' event to outbox. orderId={}", contract.getOrderId());

                } catch (Exception e) {
                        // FAILURE: Stock reservation failed, transaction will rollback
                        log.error("Stock reservation FAILED. orderId={}, reason: {}",
                                        contract.getOrderId(), e.getMessage());

                        StockReservationFailedContract replyContract = StockReservationFailedContract.builder()
                                        .orderId(contract.getOrderId())
                                        .userId(contract.getUserId())
                                        .reason(e.getMessage()) // e.g., "Insufficient stock for product 'X'"
                                        .build();

                        // Use self proxy to save failure event in a NEW transaction
                        // because the current transaction is marked for rollback
                        self.saveFailureEvent(replyContract);
                }
        }

        /**
         * Saves stock reservation failure event to outbox.
         * Uses REQUIRES_NEW transaction so it persists even when parent transaction
         * rolls back.
         */
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void saveFailureEvent(StockReservationFailedContract contract) {
                try {
                        String payload = objectMapper.writeValueAsString(contract);

                        OutboxEvent outboxEvent = OutboxEvent.builder()
                                        .aggregateType("ORDER")
                                        .aggregateId(contract.getOrderId().toString())
                                        .type("order.stock_reservation_failed")
                                        .payload(payload)
                                        .createdAt(LocalDateTime.now())
                                        .processed(false)
                                        .build();

                        outboxRepository.save(outboxEvent);
                        log.info("Saved 'order.stock_reservation_failed' event to outbox. orderId={}",
                                        contract.getOrderId());
                } catch (Exception e) {
                        log.error("Failed to save failure event to outbox", e);
                }
        }

        /**
         * Tries to create an idempotency key in a SEPARATE transaction.
         * Returns true if created successfully, false if already exists.
         * Uses REQUIRES_NEW so the key persists even if parent transaction rolls back.
         */
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public boolean tryCreateIdempotencyKey(String eventKey) {
                // Double-check pattern: first check if exists (fast path)
                if (idempotentEventRepository.existsById(eventKey)) {
                        log.info("Idempotency key already exists (duplicate detected): {}", eventKey);
                        return false;
                }

                // Then try to create (handles race condition with unique constraint)
                try {
                        idempotentEventRepository.saveAndFlush(IdempotentEvent.builder()
                                        .eventKey(eventKey)
                                        .createdAt(LocalDateTime.now())
                                        .build());
                        log.info("Created idempotency key: {}", eventKey);
                        return true;
                } catch (DataIntegrityViolationException e) {
                        log.info("Idempotency key already exists (duplicate caught by constraint): {}", eventKey);
                        return false;
                }
        }
}
