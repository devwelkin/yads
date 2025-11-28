package com.yads.courierservice.service;

import com.yads.courierservice.dto.CourierResponse;
import com.yads.courierservice.mapper.CourierMapper;
import com.yads.courierservice.model.Courier;
import com.yads.courierservice.model.CourierStatus;
import com.yads.courierservice.repository.CourierRepository;
import com.yads.common.exception.ResourceNotFoundException;
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
        return courierMapper.toCourierResponse(courierProfile, jwt);
    }

    @Override
    @Transactional
    public CourierResponse updateStatus(Jwt jwt, CourierStatus status) {
        UUID courierId = UUID.fromString(jwt.getSubject());

        log.info("Updating courier status: courierId={}, newStatus={}", courierId, status);

        Courier courier = courierRepository.findById(courierId)
                .orElseThrow(() -> {
                    log.warn("Courier not found: courierId={}", courierId);
                    return new ResourceNotFoundException("Courier not found. Please call /me first to create profile.");
                });

        CourierStatus oldStatus = courier.getStatus();
        courier.setStatus(status);
        Courier updatedCourier = courierRepository.save(courier);

        log.info("Courier status updated: courierId={}, oldStatus={}, newStatus={}",
                courierId, oldStatus, status);

        return courierMapper.toCourierResponse(updatedCourier, jwt);
    }

    @Override
    @Transactional
    public CourierResponse updateLocation(Jwt jwt, Double latitude, Double longitude) {
        UUID courierId = UUID.fromString(jwt.getSubject());

        log.info("Updating courier location: courierId={}, lat={}, lon={}", courierId, latitude, longitude);

        Courier courier = courierRepository.findById(courierId)
                .orElseThrow(() -> {
                    log.warn("Courier not found: courierId={}", courierId);
                    return new ResourceNotFoundException("Courier not found. Please call /me first to create profile.");
                });

        courier.setCurrentLatitude(latitude);
        courier.setCurrentLongitude(longitude);
        Courier updatedCourier = courierRepository.save(courier);

        log.info("Courier location updated: courierId={}, lat={}, lon={}",
                courierId, latitude, longitude);

        return courierMapper.toCourierResponse(updatedCourier, jwt);
    }
}
