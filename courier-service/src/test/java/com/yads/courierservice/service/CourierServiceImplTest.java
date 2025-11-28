package com.yads.courierservice.service;

import com.yads.courierservice.dto.CourierResponse;
import com.yads.courierservice.mapper.CourierMapper;
import com.yads.courierservice.model.Courier;
import com.yads.courierservice.model.CourierStatus;
import com.yads.courierservice.repository.CourierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CourierServiceImpl Unit Tests")
class CourierServiceImplTest {

  @Mock
  private CourierRepository courierRepository;

  @Mock
  private CourierMapper courierMapper;

  @InjectMocks
  private CourierServiceImpl service;

  private Jwt mockJwt;
  private UUID courierId;

  @BeforeEach
  void setUp() {
    courierId = UUID.randomUUID();
    mockJwt = mock(Jwt.class);
    lenient().when(mockJwt.getSubject()).thenReturn(courierId.toString());
    lenient().when(mockJwt.getClaim("name")).thenReturn("John Doe");
    lenient().when(mockJwt.getClaim("email")).thenReturn("john@example.com");
    lenient().when(mockJwt.getClaim("picture")).thenReturn("http://avatar.com/john.jpg");
  }

  @Nested
  @DisplayName("processCourierLogin Tests")
  class ProcessCourierLoginTests {

    @Test
    @DisplayName("should create new profile on first login with default OFFLINE status")
    void shouldCreateNewProfileOnFirstLogin() {
      // Arrange
      when(courierRepository.findById(courierId)).thenReturn(Optional.empty());
      when(courierRepository.save(any(Courier.class))).thenAnswer(i -> i.getArgument(0));
      when(courierMapper.toCourierResponse(any(Courier.class), eq(mockJwt)))
          .thenReturn(CourierResponse.builder().id(courierId).build());

      // Act
      CourierResponse response = service.processCourierLogin(mockJwt);

      // Assert
      ArgumentCaptor<Courier> captor = ArgumentCaptor.forClass(Courier.class);
      verify(courierRepository).save(captor.capture());

      Courier savedCourier = captor.getValue();
      assertThat(savedCourier.getId()).isEqualTo(courierId);
      assertThat(savedCourier.getStatus()).isEqualTo(CourierStatus.OFFLINE);
      assertThat(savedCourier.getIsActive()).isTrue();

      verify(courierMapper).toCourierResponse(savedCourier, mockJwt);
      assertThat(response).isNotNull();
      assertThat(response.getId()).isEqualTo(courierId);
    }

    @Test
    @DisplayName("should return existing profile when courier already exists")
    void shouldReturnExistingProfile() {
      // Arrange
      Courier existingCourier = Courier.builder()
          .id(courierId)
          .status(CourierStatus.AVAILABLE)
          .isActive(true)
          .vehiclePlate("34 ABC 123")
          .phoneNumber("+905551234567")
          .currentLatitude(40.990)
          .currentLongitude(29.020)
          .build();

      when(courierRepository.findById(courierId)).thenReturn(Optional.of(existingCourier));
      when(courierMapper.toCourierResponse(existingCourier, mockJwt))
          .thenReturn(CourierResponse.builder().id(courierId).build());

      // Act
      CourierResponse response = service.processCourierLogin(mockJwt);

      // Assert
      verify(courierRepository, never()).save(any());
      verify(courierMapper).toCourierResponse(existingCourier, mockJwt);
      assertThat(response).isNotNull();
    }

    @Test
    @DisplayName("should combine database data with JWT claims")
    void shouldCombineDatabaseDataWithJwtClaims() {
      // Arrange
      Courier existingCourier = Courier.builder()
          .id(courierId)
          .status(CourierStatus.AVAILABLE)
          .vehiclePlate("34 ABC 123")
          .build();

      CourierResponse expectedResponse = CourierResponse.builder()
          .id(courierId)
          .name("John Doe")
          .email("john@example.com")
          .profileImageUrl("http://avatar.com/john.jpg")
          .status(CourierStatus.AVAILABLE)
          .vehiclePlate("34 ABC 123")
          .build();

      when(courierRepository.findById(courierId)).thenReturn(Optional.of(existingCourier));
      when(courierMapper.toCourierResponse(existingCourier, mockJwt))
          .thenReturn(expectedResponse);

      // Act
      CourierResponse response = service.processCourierLogin(mockJwt);

      // Assert
      verify(courierMapper).toCourierResponse(existingCourier, mockJwt);
      assertThat(response.getName()).isEqualTo("John Doe");
      assertThat(response.getEmail()).isEqualTo("john@example.com");
      assertThat(response.getStatus()).isEqualTo(CourierStatus.AVAILABLE);
    }

    @Test
    @DisplayName("should use subject from JWT as courier ID")
    void shouldUseSubjectAsCourierId() {
      // Arrange
      UUID expectedId = UUID.randomUUID();
      when(mockJwt.getSubject()).thenReturn(expectedId.toString());
      when(courierRepository.findById(expectedId)).thenReturn(Optional.empty());
      when(courierRepository.save(any(Courier.class))).thenAnswer(i -> i.getArgument(0));
      when(courierMapper.toCourierResponse(any(), eq(mockJwt)))
          .thenReturn(CourierResponse.builder().build());

      // Act
      service.processCourierLogin(mockJwt);

      // Assert
      verify(courierRepository).findById(expectedId);
    }
  }
}
