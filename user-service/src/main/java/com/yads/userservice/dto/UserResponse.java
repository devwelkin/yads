package com.yads.userservice.dto;

import com.yads.common.model.Address;
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
