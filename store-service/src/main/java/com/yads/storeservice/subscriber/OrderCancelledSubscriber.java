package com.yads.storeservice.subscriber;

import com.yads.common.contracts.OrderCancelledContract;
import com.yads.common.dto.BatchReserveStockRequest;
import com.yads.storeservice.model.IdempotentEvent;
import com.yads.storeservice.repository.IdempotentEventRepository;
import com.yads.storeservice.services.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Listens to 'order.cancelled' events from order-service.
 *
 * ARCHITECTURE PRINCIPLE: Asynchronous Event-Driven Stock Restoration
 *
 * When order-service cancels an order, it immediately:
 * 1. Updates its own database (order.status = CANCELLED)
 * 2. Publishes 'order.cancelled' event to RabbitMQ
 * 3. Returns success to the user (no blocking!)
 *
 * This subscriber then:
 * 1. Receives the event from the queue
 * 2. Checks if stock restoration is needed (order was PREPARING/ON_THE_WAY)
 * 3. Restores stock for all items in the cancelled order
 *
 * Benefits:
 * - Order cancellation succeeds even if store-service is down
 * - Loose coupling between services
 * - Eventual consistency (stock restored when we process the event)
 * - No blocking HTTP calls = better user experience
 * - RabbitMQ guarantees message delivery (with acknowledgements)
 *
 * Trade-off:
 * - Slight delay between order cancellation and stock restoration (typically
 * milliseconds)
 * - Acceptable trade-off: better to let user cancel immediately, then restore
 * stock async
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderCancelledSubscriber {

    private final ProductService productService;
    private final IdempotentEventRepository idempotentEventRepository;

    /**
     * Handles 'order.cancelled' events from order-service.
     * Restores stock ONLY if the order was already accepted (PREPARING or
     * ON_THE_WAY).
     *
     * CRITICAL: Checks oldStatus to prevent GHOST INVENTORY.
     * If oldStatus=PENDING, stock was never deducted, so we skip restoration.
     */
    @RabbitListener(queues = "order_cancelled_stock_restore_queue")
    @Transactional
    public void handleOrderCancelled(OrderCancelledContract contract) {
        try {
            log.info("Received 'order.cancelled' event: orderId={}, storeId={}, oldStatus={}",
                    contract.getOrderId(), contract.getStoreId(), contract.getOldStatus());

            // Idempotency Check (First Writer Wins)
            String eventKey = "RESTORE_STOCK:" + contract.getOrderId();
            try {
                idempotentEventRepository.saveAndFlush(IdempotentEvent.builder()
                        .eventKey(eventKey)
                        .createdAt(LocalDateTime.now())
                        .build());
            } catch (DataIntegrityViolationException e) {
                log.warn("Event already processed (idempotency check). Skipping. key={}", eventKey);
                return;
            }

            // GHOST INVENTORY PREVENTION: Only restore stock if it was actually deducted
            if ("PREPARING".equals(contract.getOldStatus()) || "ON_THE_WAY".equals(contract.getOldStatus())) {

                log.info("Stock restoration needed (oldStatus={}). Restoring stock: orderId={}, itemCount={}",
                        contract.getOldStatus(), contract.getOrderId(), contract.getItems().size());

                BatchReserveStockRequest restoreRequest = BatchReserveStockRequest.builder()
                        .storeId(contract.getStoreId())
                        .items(contract.getItems())
                        .build();

                productService.batchRestoreStock(restoreRequest);

                log.info("Stock restoration completed successfully: orderId={}, storeId={}, itemCount={}",
                        contract.getOrderId(), contract.getStoreId(), contract.getItems().size());

            } else {
                // oldStatus was PENDING - stock was never deducted, so DO NOTHING
                log.info("No stock restoration needed (oldStatus={}). Skipping: orderId={}",
                        contract.getOldStatus(), contract.getOrderId());
            }

        } catch (Exception e) {
            log.error("Failed to process order.cancelled event: orderId={}. Error: {}",
                    contract.getOrderId(), e.getMessage(), e);
            // Exception will cause message to be requeued (with RabbitMQ acknowledgement)
            // This ensures eventual consistency - the message will be retried
            throw e;
        }
    }
}
