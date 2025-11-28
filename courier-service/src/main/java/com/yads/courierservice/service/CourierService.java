package com.yads.courierservice.service;

import com.yads.courierservice.dto.CourierResponse;
import com.yads.courierservice.model.CourierStatus;
import org.springframework.security.oauth2.jwt.Jwt;

public interface CourierService {
    /**
     * Processes courier login/profile retrieval.
     * If the courier doesn't exist in the database (first login), creates a new
     * profile.
     * Returns combined data from database profile and JWT claims.
     *
     * @param jwt The JWT token from Keycloak
     * @return CourierResponse with courier profile and identity information
     */
    CourierResponse processCourierLogin(Jwt jwt);

    /**
     * Updates the courier's status (AVAILABLE, BUSY, OFFLINE, ON_BREAK).
     * Only the courier themselves can update their status.
     *
     * @param jwt    The JWT token from Keycloak
     * @param status The new status to set
     * @return Updated CourierResponse
     */
    CourierResponse updateStatus(Jwt jwt, CourierStatus status);

    /**
     * Updates the courier's current location.
     * This is used for real-time tracking and courier assignment.
     *
     * @param jwt       The JWT token from Keycloak
     * @param latitude  Current latitude
     * @param longitude Current longitude
     * @return Updated CourierResponse
     */
    CourierResponse updateLocation(Jwt jwt, Double latitude, Double longitude);
}
