package com.yads.userservice.model;

import jakarta.persistence.Embeddable;
import lombok.Data;

@Data
@Embeddable
public class Address {
    private String street;
    private String city;
    private String state;
    private String postalCode;
    private String country;

    // maybe add lat/long later for geo-queries
    private Double latitude;
    private Double longitude;
}