package com.yads.courierservice.controller;

import com.yads.courierservice.dto.CourierResponse;
import com.yads.courierservice.dto.UpdateCourierLocationRequest;
import com.yads.courierservice.dto.UpdateCourierStatusRequest;
import com.yads.courierservice.service.CourierService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/couriers")
@RequiredArgsConstructor
public class CourierController {

    private final CourierService courierService;

    /**
     * Get the authenticated courier's profile.
     * On first login, this automatically creates a courier profile in the database.
     * This endpoint ensures all couriers are registered and available for delivery
     * assignments.
     *
     * @param jwt The JWT token from Keycloak (automatically injected by Spring
     *            Security)
     * @return CourierResponse containing courier profile and identity information
     */
    @GetMapping("/me")
    public ResponseEntity<CourierResponse> getMyCourierProfile(@AuthenticationPrincipal Jwt jwt) {
        CourierResponse courierResponse = courierService.processCourierLogin(jwt);
        return ResponseEntity.ok(courierResponse);
    }

    /**
     * Update the authenticated courier's status.
     * Valid statuses: AVAILABLE, BUSY, OFFLINE, ON_BREAK
     *
     * - AVAILABLE: Courier is ready to accept new deliveries
     * - BUSY: Courier is currently on a delivery (set automatically when assigned)
     * - OFFLINE: Courier is not working
     * - ON_BREAK: Courier is taking a break
     *
     * @param jwt     The JWT token from Keycloak
     * @param request The request containing the new status
     * @return Updated CourierResponse
     */
    @PatchMapping("/me/status")
    public ResponseEntity<CourierResponse> updateMyStatus(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateCourierStatusRequest request) {
        CourierResponse courierResponse = courierService.updateStatus(jwt, request.getStatus());
        return ResponseEntity.ok(courierResponse);
    }

    /**
     * Update the authenticated courier's current location.
     * This should be called periodically by the courier's mobile app
     * to enable real-time tracking and distance-based assignment.
     *
     * @param jwt     The JWT token from Keycloak
     * @param request The request containing latitude and longitude
     * @return Updated CourierResponse
     */
    @PatchMapping("/me/location")
    public ResponseEntity<CourierResponse> updateMyLocation(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateCourierLocationRequest request) {
        CourierResponse courierResponse = courierService.updateLocation(
                jwt, request.getLatitude(), request.getLongitude());
        return ResponseEntity.ok(courierResponse);
    }
}
