package com.yads.courierservice.subscriber;

import com.yads.common.contracts.OrderAssignmentContract;
import com.yads.courierservice.service.CourierAssignmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderEventSubscriber {

    private final CourierAssignmentService assignmentService;

    @RabbitListener(queues = "q.courier_service.assign_order")
    public void handleOrderPreparing(OrderAssignmentContract contract) {
        log.info("Received order.preparing event: orderId={}, storeId={}",
                contract.getOrderId(), contract.getStoreId());


        assignmentService.assignCourierToOrder(contract);
    }
}

