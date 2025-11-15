package com.yads.courierservice.service;

import com.yads.courierservice.dto.CourierResponse;
import org.springframework.security.oauth2.jwt.Jwt;

public interface CourierService {
    /**
     * Processes courier login/profile retrieval.
     * If the courier doesn't exist in the database (first login), creates a new profile.
     * Returns combined data from database profile and JWT claims.
     *
     * @param jwt The JWT token from Keycloak
     * @return CourierResponse with courier profile and identity information
     */
    CourierResponse processCourierLogin(Jwt jwt);
}

