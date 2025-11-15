package com.yads.courierservice.mapper;

import com.yads.courierservice.dto.CourierResponse;
import com.yads.courierservice.model.Courier;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class CourierMapper {

    /**
     * Maps Courier entity and JWT claims to CourierResponse DTO.
     * Combines database profile (status, vehicle, location) with live JWT data (name, email, picture).
     *
     * @param courier The courier entity from the database
     * @param jwt The JWT token containing Keycloak user claims
     * @return CourierResponse DTO with combined data
     */
    public CourierResponse toCourierResponse(Courier courier, Jwt jwt) {
        return CourierResponse.builder()
                .id(courier.getId())
                .name(jwt.getClaim("name"))           // from JWT (Keycloak)
                .email(jwt.getClaim("email"))         // from JWT (Keycloak)
                .profileImageUrl(jwt.getClaim("picture")) // from JWT (Keycloak)
                .status(courier.getStatus())          // from database
                .vehiclePlate(courier.getVehiclePlate()) // from database
                .phoneNumber(courier.getPhoneNumber()) // from database
                .isActive(courier.getIsActive())      // from database
                .currentLatitude(courier.getCurrentLatitude()) // from database
                .currentLongitude(courier.getCurrentLongitude()) // from database
                .build();
    }
}

