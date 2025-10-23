// order-service/src/main/java/com/yads/orderservice/model/Address.java
package com.yads.orderservice.model;

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
    private String addressTitle;
}