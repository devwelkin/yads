package com.yads.courierservice.mapper;

import com.yads.courierservice.dto.CourierResponse;
import com.yads.courierservice.model.Courier;
import com.yads.courierservice.model.CourierStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

@DisplayName("CourierMapper Unit Tests")
class CourierMapperTest {

  private CourierMapper mapper;
  private Jwt mockJwt;
  private UUID courierId;

  @BeforeEach
  void setUp() {
    mapper = new CourierMapper();
    courierId = UUID.randomUUID();

    mockJwt = mock(Jwt.class);
    lenient().when(mockJwt.getClaim("name")).thenReturn("Ali Yilmaz");
    lenient().when(mockJwt.getClaim("email")).thenReturn("ali@example.com");
    lenient().when(mockJwt.getClaim("picture")).thenReturn("http://avatar.com/ali.jpg");
  }

  @Test
  @DisplayName("should map all fields from Courier entity correctly")
  void shouldMapAllFieldsFromCourierEntity() {
    // Arrange
    Courier courier = Courier.builder()
        .id(courierId)
        .status(CourierStatus.AVAILABLE)
        .vehiclePlate("34 XYZ 789")
        .phoneNumber("+905559876543")
        .isActive(true)
        .currentLatitude(41.015)
        .currentLongitude(28.979)
        .build();

    // Act
    CourierResponse response = mapper.toCourierResponse(courier, mockJwt);

    // Assert
    assertThat(response.getId()).isEqualTo(courierId);
    assertThat(response.getStatus()).isEqualTo(CourierStatus.AVAILABLE);
    assertThat(response.getVehiclePlate()).isEqualTo("34 XYZ 789");
    assertThat(response.getPhoneNumber()).isEqualTo("+905559876543");
    assertThat(response.getIsActive()).isTrue();
    assertThat(response.getCurrentLatitude()).isEqualTo(41.015);
    assertThat(response.getCurrentLongitude()).isEqualTo(28.979);
  }

  @Test
  @DisplayName("should map all claims from JWT correctly")
  void shouldMapAllClaimsFromJwt() {
    // Arrange
    Courier courier = Courier.builder()
        .id(courierId)
        .status(CourierStatus.OFFLINE)
        .build();

    // Act
    CourierResponse response = mapper.toCourierResponse(courier, mockJwt);

    // Assert
    assertThat(response.getName()).isEqualTo("Ali Yilmaz");
    assertThat(response.getEmail()).isEqualTo("ali@example.com");
    assertThat(response.getProfileImageUrl()).isEqualTo("http://avatar.com/ali.jpg");
  }

  @Test
  @DisplayName("should combine database data and JWT data correctly")
  void shouldCombineDatabaseAndJwtData() {
    // Arrange
    Courier courier = Courier.builder()
        .id(courierId)
        .status(CourierStatus.BUSY)
        .vehiclePlate("06 TEST 123")
        .phoneNumber("+905551112233")
        .isActive(true)
        .currentLatitude(39.925)
        .currentLongitude(32.837)
        .build();

    // Act
    CourierResponse response = mapper.toCourierResponse(courier, mockJwt);

    // Assert - Database fields
    assertThat(response.getId()).isEqualTo(courierId);
    assertThat(response.getStatus()).isEqualTo(CourierStatus.BUSY);
    assertThat(response.getVehiclePlate()).isEqualTo("06 TEST 123");
    assertThat(response.getCurrentLatitude()).isEqualTo(39.925);

    // Assert - JWT fields
    assertThat(response.getName()).isEqualTo("Ali Yilmaz");
    assertThat(response.getEmail()).isEqualTo("ali@example.com");
  }

  @Test
  @DisplayName("should handle null optional fields gracefully")
  void shouldHandleNullOptionalFields() {
    // Arrange
    Courier courier = Courier.builder()
        .id(courierId)
        .status(CourierStatus.AVAILABLE)
        .isActive(true)
        // No vehicle plate, phone, or location
        .build();

    // Act
    CourierResponse response = mapper.toCourierResponse(courier, mockJwt);

    // Assert
    assertThat(response.getId()).isEqualTo(courierId);
    assertThat(response.getVehiclePlate()).isNull();
    assertThat(response.getPhoneNumber()).isNull();
    assertThat(response.getCurrentLatitude()).isNull();
    assertThat(response.getCurrentLongitude()).isNull();
  }
}
