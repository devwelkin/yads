package com.yads.courierservice;

import com.yads.courierservice.model.Courier;
import com.yads.courierservice.model.CourierStatus;
import com.yads.courierservice.repository.CourierRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for courier login and profile creation.
 *
 * Tests the /api/v1/couriers/me endpoint which:
 * - Creates a courier profile on first login
 * - Returns existing profile on subsequent logins
 * - Maps JWT claims to courier response
 */
@AutoConfigureMockMvc
public class CourierLoginIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private CourierRepository courierRepository;

  @BeforeEach
  @AfterEach
  void cleanup() {
    courierRepository.deleteAll();
  }

  @Test
  void should_create_courier_profile_on_first_login() throws Exception {
    // ARRANGE
    UUID courierId = UUID.randomUUID();
    String courierEmail = "courier@example.com";
    String courierName = "John Doe";

    // ACT: First login - courier doesn't exist in DB yet
    mockMvc.perform(get("/api/v1/couriers/me")
        .with(jwt().jwt(builder -> builder
            .subject(courierId.toString())
            .claim("email", courierEmail)
            .claim("name", courierName))))
        .andExpect(status().isOk());

    // ASSERT: Courier profile created in DB
    Courier savedCourier = courierRepository.findById(courierId).orElseThrow();
    assertEquals(courierId, savedCourier.getId());
    assertEquals(CourierStatus.OFFLINE, savedCourier.getStatus());
    assertTrue(savedCourier.getIsActive());
    assertNull(savedCourier.getVehiclePlate());
    assertNull(savedCourier.getCurrentLatitude());
    assertNull(savedCourier.getCurrentLongitude());
  }

  @Test
  void should_return_existing_profile_on_subsequent_login() throws Exception {
    // ARRANGE: Create existing courier in DB
    UUID courierId = UUID.randomUUID();
    Courier existingCourier = new Courier();
    existingCourier.setId(courierId);
    existingCourier.setStatus(CourierStatus.AVAILABLE);
    existingCourier.setIsActive(true);
    existingCourier.setVehiclePlate("34-ABC-123");
    existingCourier.setCurrentLatitude(40.990);
    existingCourier.setCurrentLongitude(29.020);
    courierRepository.save(existingCourier);

    // ACT: Login with existing courier
    mockMvc.perform(get("/api/v1/couriers/me")
        .with(jwt().jwt(builder -> builder
            .subject(courierId.toString())
            .claim("email", "courier@example.com")
            .claim("name", "John Doe"))))
        .andExpect(status().isOk());

    // ASSERT: Existing profile returned (not created again)
    Courier finalCourier = courierRepository.findById(courierId).orElseThrow();
    assertEquals(CourierStatus.AVAILABLE, finalCourier.getStatus());
    assertTrue(finalCourier.getIsActive());

    // Verify only one courier exists (not duplicated)
    long count = courierRepository.count();
    assertEquals(1, count);
  }

  @Test
  void should_handle_multiple_couriers_independently() throws Exception {
    // ARRANGE: Two different couriers
    UUID courier1Id = UUID.randomUUID();
    UUID courier2Id = UUID.randomUUID();

    // ACT: Both couriers login
    mockMvc.perform(get("/api/v1/couriers/me")
        .with(jwt().jwt(builder -> builder
            .subject(courier1Id.toString())
            .claim("email", "courier1@example.com")
            .claim("name", "Courier One"))))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/v1/couriers/me")
        .with(jwt().jwt(builder -> builder
            .subject(courier2Id.toString())
            .claim("email", "courier2@example.com")
            .claim("name", "Courier Two"))))
        .andExpect(status().isOk());

    // ASSERT: Both couriers exist independently
    assertEquals(2, courierRepository.count());

    Courier courier1 = courierRepository.findById(courier1Id).orElseThrow();
    Courier courier2 = courierRepository.findById(courier2Id).orElseThrow();

    assertEquals(CourierStatus.OFFLINE, courier1.getStatus());
    assertEquals(CourierStatus.OFFLINE, courier2.getStatus());
    assertTrue(courier1.getIsActive());
    assertTrue(courier2.getIsActive());
  }
}
