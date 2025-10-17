package com.fitness.userservice.service;

import com.fitness.userservice.dto.UserResponse;
import org.springframework.security.oauth2.jwt.Jwt;

public interface UserService {
    UserResponse processUserLogin(Jwt jwt);
}
