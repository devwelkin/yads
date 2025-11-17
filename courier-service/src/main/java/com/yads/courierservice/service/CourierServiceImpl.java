package com.yads.courierservice.service;

import com.yads.courierservice.dto.CourierResponse;
import com.yads.courierservice.mapper.CourierMapper;
import com.yads.courierservice.model.Courier;
import com.yads.courierservice.model.CourierStatus;
import com.yads.courierservice.repository.CourierRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CourierServiceImpl implements CourierService {

    private final CourierRepository courierRepository;
    private final CourierMapper courierMapper;

    @Override
    @Transactional
    public CourierResponse processCourierLogin(Jwt jwt) {
        UUID courierId = UUID.fromString(jwt.getSubject());

        log.info("Processing courier login for courierId: {}", courierId);

        // Find the courier profile in our database.
        // If it doesn't exist (e.g., first login), create a new profile with default values.
        // This ensures we always have a record for courier assignment and tracking.
        Courier courierProfile = courierRepository.findById(courierId)
                .orElseGet(() -> {
                    log.info("Courier {} not found in database. Creating new profile (first login).", courierId);

                    // Create new courier with minimal initial data
                    Courier newCourier = new Courier();
                    newCourier.setId(courierId);
                    newCourier.setStatus(CourierStatus.OFFLINE); // Default to OFFLINE on first login
                    newCourier.setIsActive(true); // Courier is active and can be assigned deliveries

                    return courierRepository.save(newCourier);
                });

        log.info("Courier profile loaded/created: id={}, status={}, isActive={}",
                courierProfile.getId(), courierProfile.getStatus(), courierProfile.getIsActive());

        // Assemble the response using data from both the database profile
        // (status, vehicle, location) and the live JWT (name, email, picture)
        return courierMapper.toCourierResponse(courierProfile, jwt);
    }
}

