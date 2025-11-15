package com.yads.storeservice.dto;

import com.yads.common.model.Address;
import com.yads.storeservice.model.StoreType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class StoreRequest {

    @NotBlank(message = "Store name cannot be blank")
    @Size(min = 2, max = 100)
    private String name;
    private String description;
    private StoreType storeType;
    private Address address;
}
