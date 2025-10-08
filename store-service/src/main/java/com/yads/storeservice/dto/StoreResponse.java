package com.yads.storeservice.dto;

import com.yads.storeservice.model.Address;
import com.yads.storeservice.model.StoreType;
import lombok.Data;

import java.util.UUID;

@Data
public class StoreResponse {
    private UUID id;
    private String name;
    private String description;
    private StoreType storeType;
    private Boolean isActive;
    private Address address;
}
