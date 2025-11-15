package com.yads.courierservice.controller;

import com.yads.courierservice.dto.CourierResponse;
import com.yads.courierservice.service.CourierService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/couriers")
@RequiredArgsConstructor
public class CourierController {

    private final CourierService courierService;

    /**
     * Get the authenticated courier's profile.
     * On first login, this automatically creates a courier profile in the database.
     * This endpoint ensures all couriers are registered and available for delivery assignments.
     *
     * @param jwt The JWT token from Keycloak (automatically injected by Spring Security)
     * @return CourierResponse containing courier profile and identity information
     */
    @GetMapping("/me")
    public ResponseEntity<CourierResponse> getMyCourierProfile(@AuthenticationPrincipal Jwt jwt) {
        CourierResponse courierResponse = courierService.processCourierLogin(jwt);
        return ResponseEntity.ok(courierResponse);
    }
}

