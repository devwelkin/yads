package com.yads.courierservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yads.common.contracts.CourierAssignedContract;
import com.yads.common.contracts.CourierAssignmentFailedContract;
import com.yads.common.contracts.OrderAssignmentContract;
import com.yads.common.model.Address;
import com.yads.courierservice.model.Courier;
import com.yads.courierservice.model.CourierStatus;
import com.yads.courierservice.model.OutboxEvent;
import com.yads.courierservice.repository.CourierRepository;
import com.yads.courierservice.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CourierAssignmentService Unit Tests")
class CourierAssignmentServiceTest {

    @Mock
    private CourierRepository courierRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private CourierAssignmentService service;

    private CourierAssignmentService serviceSpy;

    private UUID orderId;
    private UUID storeId;
    private UUID userId;
    private OrderAssignmentContract contract;
    private Address pickupAddress;

    @BeforeEach
    void setUp() {
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        orderId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        userId = UUID.randomUUID();

        pickupAddress = new Address();
        pickupAddress.setLatitude(40.990);
        pickupAddress.setLongitude(29.020);

        contract = OrderAssignmentContract.builder()
                .orderId(orderId)
                .storeId(storeId)
                .userId(userId)
                .pickupAddress(pickupAddress)
                .shippingAddress(new Address())
                .build();

        // Setup spy for self-reference pattern
        serviceSpy = spy(service);
        ReflectionTestUtils.setField(service, "self", serviceSpy);
    }

    @Nested
    @DisplayName("assignCourierToOrder Tests")
    class AssignCourierToOrderTests {

        @Test
        @DisplayName("should assign nearest available courier successfully")
        void shouldAssignNearestAvailableCourier() {
            // Arrange
            Courier nearCourier = createCourier(CourierStatus.AVAILABLE, 40.980, 29.025);
            Courier farCourier = createCourier(CourierStatus.AVAILABLE, 40.950, 29.090);

            when(courierRepository.findByStatusAndIsActiveTrue(CourierStatus.AVAILABLE))
                    .thenReturn(new ArrayList<>(List.of(nearCourier, farCourier)));
            when(serviceSpy.atomicAssignIfAvailable(nearCourier.getId(), contract))
                    .thenReturn(true);

            // Act
            service.assignCourierToOrder(contract);

            // Assert
            verify(serviceSpy).atomicAssignIfAvailable(eq(nearCourier.getId()), eq(contract));
            verify(serviceSpy, never()).atomicAssignIfAvailable(eq(farCourier.getId()), any());
            verify(outboxRepository, never())
                    .save(argThat(event -> event.getType().equals("courier.assignment.failed")));
        }

        @Test
        @DisplayName("should try next courier when first one becomes unavailable")
        void shouldTryNextCourierWhenFirstClaimedByOther() {
            // Arrange
            Courier courier1 = createCourier(CourierStatus.AVAILABLE, 40.980, 29.025);
            Courier courier2 = createCourier(CourierStatus.AVAILABLE, 40.970, 29.050);

            when(courierRepository.findByStatusAndIsActiveTrue(CourierStatus.AVAILABLE))
                    .thenReturn(new ArrayList<>(List.of(courier1, courier2)));
            when(serviceSpy.atomicAssignIfAvailable(courier1.getId(), contract))
                    .thenReturn(false); // First courier claimed
            when(serviceSpy.atomicAssignIfAvailable(courier2.getId(), contract))
                    .thenReturn(true); // Second courier succeeds

            // Act
            service.assignCourierToOrder(contract);

            // Assert
            verify(serviceSpy).atomicAssignIfAvailable(courier1.getId(), contract);
            verify(serviceSpy).atomicAssignIfAvailable(courier2.getId(), contract);
        }

        @Test
        @DisplayName("should publish failed event when no couriers available")
        void shouldPublishFailedEventWhenNoCouriersAvailable() {
            // Arrange
            when(courierRepository.findByStatusAndIsActiveTrue(CourierStatus.AVAILABLE))
                    .thenReturn(List.of());

            // Act
            service.assignCourierToOrder(contract);

            // Assert
            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxRepository).save(captor.capture());

            OutboxEvent event = captor.getValue();
            assertThat(event.getType()).isEqualTo("courier.assignment.failed");
            assertThat(event.getAggregateType()).isEqualTo("ORDER");
            assertThat(event.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(event.getPayload()).contains("No available couriers");
        }

        @Test
        @DisplayName("should publish failed event when all couriers claimed during process")
        void shouldPublishFailedEventWhenAllCouriersClaimed() {
            // Arrange
            Courier courier1 = createCourier(CourierStatus.AVAILABLE, 40.980, 29.025);
            Courier courier2 = createCourier(CourierStatus.AVAILABLE, 40.970, 29.050);

            when(courierRepository.findByStatusAndIsActiveTrue(CourierStatus.AVAILABLE))
                    .thenReturn(new ArrayList<>(List.of(courier1, courier2)));
            when(serviceSpy.atomicAssignIfAvailable(any(), eq(contract)))
                    .thenReturn(false); // All couriers claimed

            // Act
            service.assignCourierToOrder(contract);

            // Assert
            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxRepository).save(captor.capture());

            OutboxEvent event = captor.getValue();
            assertThat(event.getType()).isEqualTo("courier.assignment.failed");
            assertThat(event.getPayload()).contains("All 2 available couriers were claimed");
        }

        @Test
        @DisplayName("should retry next courier on optimistic locking failure")
        void shouldRetryNextCourierOnOptimisticLockFailure() {
            // Arrange
            Courier courier1 = createCourier(CourierStatus.AVAILABLE, 40.980, 29.025);
            Courier courier2 = createCourier(CourierStatus.AVAILABLE, 40.970, 29.050);

            when(courierRepository.findByStatusAndIsActiveTrue(CourierStatus.AVAILABLE))
                    .thenReturn(new ArrayList<>(List.of(courier1, courier2)));
            when(serviceSpy.atomicAssignIfAvailable(courier1.getId(), contract))
                    .thenThrow(new OptimisticLockingFailureException("Version mismatch"));
            when(serviceSpy.atomicAssignIfAvailable(courier2.getId(), contract))
                    .thenReturn(true);

            // Act
            service.assignCourierToOrder(contract);

            // Assert
            verify(serviceSpy).atomicAssignIfAvailable(courier1.getId(), contract);
            verify(serviceSpy).atomicAssignIfAvailable(courier2.getId(), contract);
        }

        @Test
        @DisplayName("should continue to next courier when unexpected exception occurs")
        void shouldContinueOnUnexpectedException() {
            // Arrange
            Courier courier1 = createCourier(CourierStatus.AVAILABLE, 40.980, 29.025);
            Courier courier2 = createCourier(CourierStatus.AVAILABLE, 40.970, 29.050);

            when(courierRepository.findByStatusAndIsActiveTrue(CourierStatus.AVAILABLE))
                    .thenReturn(new ArrayList<>(List.of(courier1, courier2)));
            when(serviceSpy.atomicAssignIfAvailable(courier1.getId(), contract))
                    .thenThrow(new RuntimeException("Database connection error"));
            when(serviceSpy.atomicAssignIfAvailable(courier2.getId(), contract))
                    .thenReturn(true);

            // Act
            service.assignCourierToOrder(contract);

            // Assert
            verify(serviceSpy).atomicAssignIfAvailable(courier1.getId(), contract);
            verify(serviceSpy).atomicAssignIfAvailable(courier2.getId(), contract);
        }
    }

    @Nested
    @DisplayName("atomicAssignIfAvailable Tests")
    class AtomicAssignIfAvailableTests {

        @Test
        @DisplayName("should mark courier as BUSY and create outbox event successfully")
        void shouldMarkCourierBusyAndCreateOutbox() throws Exception {
            // Arrange
            Courier courier = createCourier(CourierStatus.AVAILABLE, 40.980, 29.025);

            when(courierRepository.findByIdWithLock(courier.getId()))
                    .thenReturn(Optional.of(courier));
            when(courierRepository.save(any(Courier.class))).thenReturn(courier);

            // Act
            boolean result = service.atomicAssignIfAvailable(courier.getId(), contract);

            // Assert
            assertThat(result).isTrue();
            assertThat(courier.getStatus()).isEqualTo(CourierStatus.BUSY);

            verify(courierRepository).save(courier);

            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxRepository).save(captor.capture());

            OutboxEvent event = captor.getValue();
            assertThat(event.getType()).isEqualTo("courier.assigned");
            assertThat(event.getAggregateType()).isEqualTo("COURIER");
            assertThat(event.getAggregateId()).isEqualTo(courier.getId().toString());
            assertThat(event.isProcessed()).isFalse();

            // Verify payload
            CourierAssignedContract payload = objectMapper.readValue(
                    event.getPayload(), CourierAssignedContract.class);
            assertThat(payload.getOrderId()).isEqualTo(orderId);
            assertThat(payload.getCourierId()).isEqualTo(courier.getId());
            assertThat(payload.getStoreId()).isEqualTo(storeId);
            assertThat(payload.getUserId()).isEqualTo(userId);
        }

        @Test
        @DisplayName("should return false when courier not found")
        void shouldReturnFalseWhenCourierNotFound() {
            // Arrange
            UUID courierId = UUID.randomUUID();
            when(courierRepository.findByIdWithLock(courierId))
                    .thenReturn(Optional.empty());

            // Act
            boolean result = service.atomicAssignIfAvailable(courierId, contract);

            // Assert
            assertThat(result).isFalse();
            verify(courierRepository, never()).save(any());
            verify(outboxRepository, never()).save(any());
        }

        @Test
        @DisplayName("should return false when courier is not available")
        void shouldReturnFalseWhenCourierNotAvailable() {
            // Arrange
            Courier busyCourier = createCourier(CourierStatus.BUSY, 40.980, 29.025);

            when(courierRepository.findByIdWithLock(busyCourier.getId()))
                    .thenReturn(Optional.of(busyCourier));

            // Act
            boolean result = service.atomicAssignIfAvailable(busyCourier.getId(), contract);

            // Assert
            assertThat(result).isFalse();
            verify(courierRepository, never()).save(any());
            verify(outboxRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw exception when outbox creation fails")
        void shouldThrowExceptionWhenOutboxCreationFails() {
            // Arrange
            Courier courier = createCourier(CourierStatus.AVAILABLE, 40.980, 29.025);

            when(courierRepository.findByIdWithLock(courier.getId()))
                    .thenReturn(Optional.of(courier));
            when(courierRepository.save(any(Courier.class))).thenReturn(courier);
            when(outboxRepository.save(any(OutboxEvent.class)))
                    .thenThrow(new RuntimeException("Database error"));

            // Act & Assert
            try {
                service.atomicAssignIfAvailable(courier.getId(), contract);
            } catch (RuntimeException e) {
                // Expected
            }

            verify(courierRepository).save(courier);
        }
    }

    @Nested
    @DisplayName("selectBestCouriers Tests")
    class SelectBestCouriersTests {

        @Test
        @DisplayName("should sort couriers by distance ascending")
        void shouldSortCouriersByDistanceAscending() {
            // Arrange
            Courier farCourier = createCourier(CourierStatus.AVAILABLE, 40.950, 29.090);
            Courier nearCourier = createCourier(CourierStatus.AVAILABLE, 40.980, 29.025);
            Courier mediumCourier = createCourier(CourierStatus.AVAILABLE, 40.970, 29.050);

            when(courierRepository.findByStatusAndIsActiveTrue(CourierStatus.AVAILABLE))
                    .thenReturn(new ArrayList<>(List.of(farCourier, nearCourier, mediumCourier)));
            when(serviceSpy.atomicAssignIfAvailable(any(), any())).thenReturn(true);

            // Act
            service.assignCourierToOrder(contract);

            // Assert - Should try nearest first
            verify(serviceSpy).atomicAssignIfAvailable(nearCourier.getId(), contract);
        }

        @Test
        @DisplayName("should return empty list when no couriers available")
        void shouldReturnEmptyWhenNoCouriersAvailable() {
            // Arrange
            when(courierRepository.findByStatusAndIsActiveTrue(CourierStatus.AVAILABLE))
                    .thenReturn(List.of());

            // Act
            service.assignCourierToOrder(contract);

            // Assert
            verify(serviceSpy, never()).atomicAssignIfAvailable(any(), any());
        }

        @Test
        @DisplayName("should filter out couriers without location data")
        void shouldFilterOutCouriersWithoutLocation() {
            // Arrange
            Courier noLocationCourier = createCourier(CourierStatus.AVAILABLE, null, null);
            Courier validCourier = createCourier(CourierStatus.AVAILABLE, 40.980, 29.025);

            when(courierRepository.findByStatusAndIsActiveTrue(CourierStatus.AVAILABLE))
                    .thenReturn(new ArrayList<>(List.of(noLocationCourier, validCourier)));
            when(serviceSpy.atomicAssignIfAvailable(any(), any())).thenReturn(true);

            // Act
            service.assignCourierToOrder(contract);

            // Assert - Only valid courier should be tried
            verify(serviceSpy).atomicAssignIfAvailable(validCourier.getId(), contract);
            verify(serviceSpy, never()).atomicAssignIfAvailable(noLocationCourier.getId(), contract);
        }

        @Test
        @DisplayName("should return couriers unsorted when pickup address missing coordinates")
        void shouldReturnUnsortedWhenPickupMissingCoordinates() {
            // Arrange
            pickupAddress.setLatitude(null);
            pickupAddress.setLongitude(null);

            Courier courier1 = createCourier(CourierStatus.AVAILABLE, 40.980, 29.025);
            Courier courier2 = createCourier(CourierStatus.AVAILABLE, 40.970, 29.050);

            when(courierRepository.findByStatusAndIsActiveTrue(CourierStatus.AVAILABLE))
                    .thenReturn(new ArrayList<>(List.of(courier1, courier2)));
            when(serviceSpy.atomicAssignIfAvailable(any(), any())).thenReturn(true);

            // Act
            service.assignCourierToOrder(contract);

            // Assert - Any courier might be tried first (unsorted)
            verify(serviceSpy, atLeastOnce()).atomicAssignIfAvailable(any(), eq(contract));
        }

        @Test
        @DisplayName("should filter all couriers when all missing location data")
        void shouldReturnEmptyWhenAllCouriersMissingLocation() {
            // Arrange
            Courier courier1 = createCourier(CourierStatus.AVAILABLE, null, null);
            Courier courier2 = createCourier(CourierStatus.AVAILABLE, null, 29.025);
            Courier courier3 = createCourier(CourierStatus.AVAILABLE, 40.980, null);

            when(courierRepository.findByStatusAndIsActiveTrue(CourierStatus.AVAILABLE))
                    .thenReturn(new ArrayList<>(List.of(courier1, courier2, courier3)));

            // Act
            service.assignCourierToOrder(contract);

            // Assert
            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxRepository).save(captor.capture());
            assertThat(captor.getValue().getType()).isEqualTo("courier.assignment.failed");
        }
    }

    @Nested
    @DisplayName("publishCourierAssignmentFailed Tests")
    class PublishCourierAssignmentFailedTests {

        @Test
        @DisplayName("should save outbox event with correct fields")
        void shouldSaveOutboxEventWithCorrectFields() throws Exception {
            // Arrange
            when(courierRepository.findByStatusAndIsActiveTrue(CourierStatus.AVAILABLE))
                    .thenReturn(List.of());

            // Act
            service.assignCourierToOrder(contract);

            // Assert
            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxRepository).save(captor.capture());

            OutboxEvent event = captor.getValue();
            assertThat(event.getAggregateType()).isEqualTo("ORDER");
            assertThat(event.getAggregateId()).isEqualTo(orderId.toString());
            assertThat(event.getType()).isEqualTo("courier.assignment.failed");
            assertThat(event.isProcessed()).isFalse();

            // Verify payload
            CourierAssignmentFailedContract payload = objectMapper.readValue(
                    event.getPayload(), CourierAssignmentFailedContract.class);
            assertThat(payload.getOrderId()).isEqualTo(orderId);
            assertThat(payload.getUserId()).isEqualTo(userId);
            assertThat(payload.getStoreId()).isEqualTo(storeId);
            assertThat(payload.getReason()).isNotBlank();
        }

        @Test
        @DisplayName("should not throw exception when outbox save fails")
        void shouldNotThrowWhenOutboxSaveFails() {
            // Arrange
            when(courierRepository.findByStatusAndIsActiveTrue(CourierStatus.AVAILABLE))
                    .thenReturn(List.of());
            when(outboxRepository.save(any(OutboxEvent.class)))
                    .thenThrow(new RuntimeException("Database error"));

            // Act & Assert - Should not throw
            service.assignCourierToOrder(contract);

            verify(outboxRepository).save(any(OutboxEvent.class));
        }
    }

    private Courier createCourier(CourierStatus status, Double latitude, Double longitude) {
        return Courier.builder()
                .id(UUID.randomUUID())
                .status(status)
                .isActive(true)
                .currentLatitude(latitude)
                .currentLongitude(longitude)
                .version(0L)
                .build();
    }
}