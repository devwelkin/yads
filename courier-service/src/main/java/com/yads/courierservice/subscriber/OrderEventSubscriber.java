package com.yads.courierservice.subscriber;

import com.yads.common.contracts.OrderAssignmentContract;
import com.yads.courierservice.config.AmqpConfig;
import com.yads.courierservice.model.IdempotentEvent;
import com.yads.courierservice.repository.IdempotentEventRepository;
import com.yads.courierservice.service.CourierAssignmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderEventSubscriber {

    private final CourierAssignmentService assignmentService;
    private final IdempotentEventRepository idempotentEventRepository;

    @RabbitListener(queues = AmqpConfig.Q_ASSIGN_ORDER)
    @Transactional
    public void handleOrderPreparing(OrderAssignmentContract contract) {
        log.info("Received order.preparing event: orderId={}, storeId={}",
                contract.getOrderId(), contract.getStoreId());

        // Idempotency Check (First Writer Wins)
        String eventKey = "ASSIGN_COURIER:" + contract.getOrderId();
        try {
            idempotentEventRepository.saveAndFlush(IdempotentEvent.builder()
                    .eventKey(eventKey)
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (DataIntegrityViolationException e) {
            log.warn("Event already processed (idempotency check). Skipping. key={}", eventKey);
            return;
        }

        assignmentService.assignCourierToOrder(contract);
    }
}
