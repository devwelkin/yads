package com.yads.storeservice.subscriber;

import com.yads.common.contracts.StockReservationFailedContract;
import com.yads.common.contracts.StockReservationRequestContract;
import com.yads.common.contracts.StockReservedContract;
import com.yads.common.dto.BatchReserveStockRequest;
import com.yads.storeservice.model.Store;
import com.yads.storeservice.repository.StoreRepository;
import com.yads.storeservice.services.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

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
    private final RabbitTemplate rabbitTemplate;

    /**
     * Handles stock reservation requests from order-service.
     * Attempts to reserve stock, fetches store's pickup address from DB,
     * and publishes either success or failure event.
     */
    @Transactional
    @RabbitListener(queues = "q.store_service.stock_reservation_request")
    public void handleStockReservationRequest(StockReservationRequestContract contract) {
        log.info("Received 'order.stock_reservation.requested' event. orderId={}, storeId={}",
                contract.getOrderId(), contract.getStoreId());

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
                    .orElseThrow(() -> new RuntimeException("Store not found: " + contract.getStoreId()));

            StockReservedContract replyContract = StockReservedContract.builder()
                    .orderId(contract.getOrderId())
                    .storeId(contract.getStoreId())
                    .userId(contract.getUserId())
                    .pickupAddress(store.getAddress()) // From store-service's own DB
                    .shippingAddress(contract.getShippingAddress())
                    .build();

            rabbitTemplate.convertAndSend("order_events_exchange", "order.stock_reserved", replyContract);
            log.info("Sent 'order.stock_reserved' event. orderId={}", contract.getOrderId());

        } catch (Exception e) {
            // FAILURE: Stock reservation failed, transaction will rollback
            log.error("Stock reservation FAILED. orderId={}, reason: {}",
                    contract.getOrderId(), e.getMessage());

            StockReservationFailedContract replyContract = StockReservationFailedContract.builder()
                    .orderId(contract.getOrderId())
                    .userId(contract.getUserId())
                    .reason(e.getMessage()) // e.g., "Insufficient stock for product 'X'"
                    .build();

            rabbitTemplate.convertAndSend("order_events_exchange", "order.stock_reservation_failed", replyContract);
            log.info("Sent 'order.stock_reservation_failed' event. orderId={}, reason: {}",
                    contract.getOrderId(), e.getMessage());
        }
    }
}

