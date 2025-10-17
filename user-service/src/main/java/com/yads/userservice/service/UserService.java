package com.yads.userservice.service;

import com.yads.userservice.dto.UserResponse;
import org.springframework.security.oauth2.jwt.Jwt;

public interface UserService {
    UserResponse processUserLogin(Jwt jwt);
}
