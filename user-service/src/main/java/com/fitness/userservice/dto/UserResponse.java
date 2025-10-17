package com.fitness.userservice.dto;

import com.fitness.userservice.model.Address;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Builder
public class UserResponse {
    private UUID id;
    private String name;
    private String email;
    private String profileImageUrl;
    private List<Address> addresses;
}
