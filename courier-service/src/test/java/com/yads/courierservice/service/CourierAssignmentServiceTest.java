package com.yads.courierservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yads.common.contracts.OrderAssignmentContract;
import com.yads.common.model.Address;
import com.yads.courierservice.model.Courier;
import com.yads.courierservice.model.CourierStatus;
import com.yads.courierservice.model.OutboxEvent;
import com.yads.courierservice.repository.CourierRepository;
import com.yads.courierservice.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CourierAssignmentServiceTest {

    @Mock
    private CourierRepository courierRepository;
    @Mock
    private OutboxRepository outboxRepository;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    // Self-injection mock
    @Mock
    private CourierAssignmentService self;

    @InjectMocks
    private CourierAssignmentService service;

    private OrderAssignmentContract contract;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        orderId = UUID.randomUUID();
        Address storeAddress = new Address();
        // Istanbul - Kadikoy (Merkez)
        storeAddress.setLatitude(40.990);
        storeAddress.setLongitude(29.020);
        ReflectionTestUtils.setField(service, "self", self);

        contract = OrderAssignmentContract.builder()
                .orderId(orderId)
                .pickupAddress(storeAddress)
                .build();
    }

    @Test
    void assignCourierToOrder_SortsByDistance() {
        // Arrange
        // Courier 1: Near (Kadikoy)
        Courier c1 = Courier.builder().id(UUID.randomUUID())
                .currentLatitude(40.980).currentLongitude(29.025) // ~1.2 km
                .status(CourierStatus.AVAILABLE).isActive(true).build();

        // Courier 2: Far (Bostanci)
        Courier c2 = Courier.builder().id(UUID.randomUUID())
                .currentLatitude(40.950).currentLongitude(29.090) // ~8 km
                .status(CourierStatus.AVAILABLE).isActive(true).build();

        // Mock repository to return couriers in reverse order
        when(courierRepository.findByStatusAndIsActiveTrue(CourierStatus.AVAILABLE))
                .thenReturn(new ArrayList<>(List.of(c2, c1)));

        // Mock self.atomicAssignIfAvailable to always return true for simplicity
        when(self.atomicAssignIfAvailable(any(), any())).thenReturn(true);

        // Act
        service.assignCourierToOrder(contract);

        // Assert
        // First, assignment should be attempted for the nearest courier (c1)
        ArgumentCaptor<UUID> idCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(self, atLeastOnce()).atomicAssignIfAvailable(idCaptor.capture(), eq(contract));

        assertThat(idCaptor.getAllValues().get(0)).isEqualTo(c1.getId());
    }

    @Test
    void atomicAssignIfAvailable_Success() {
        // Arrange
        UUID courierId = UUID.randomUUID();
        Courier courier = Courier.builder()
                .id(courierId)
                .status(CourierStatus.AVAILABLE)
                .version(1L)
                .build();

        when(courierRepository.findByIdWithLock(courierId)).thenReturn(Optional.of(courier));

        // Act
        // Call the method under test directly since we are testing the logic in a unit
        // test
        boolean result = service.atomicAssignIfAvailable(courierId, contract);

        // Assert
        assertThat(result).isTrue();
        assertThat(courier.getStatus()).isEqualTo(CourierStatus.BUSY); // Status should change
        verify(courierRepository).save(courier);
        verify(outboxRepository).save(any(OutboxEvent.class)); // Event should be published
    }

    @Test
    void atomicAssignIfAvailable_Fail_AlreadyBusy() {
        // Arrange
        UUID courierId = UUID.randomUUID();
        // Courier appears as BUSY in the database (taken by another thread)
        Courier courier = Courier.builder().id(courierId).status(CourierStatus.BUSY).build();

        when(courierRepository.findByIdWithLock(courierId)).thenReturn(Optional.of(courier));

        // Act
        boolean result = service.atomicAssignIfAvailable(courierId, contract);

        // Assert
        assertThat(result).isFalse(); // Assignment should fail
        verify(courierRepository, never()).save(any()); // Save should not be called
        verify(outboxRepository, never()).save(any()); // Event should not be published
    }
}