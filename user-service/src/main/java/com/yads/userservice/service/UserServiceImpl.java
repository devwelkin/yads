package com.yads.userservice.service;

import com.yads.userservice.dto.UserResponse;
import com.yads.userservice.model.User;
import com.yads.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;

    public UserResponse processUserLogin(Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());

        // ensure user exists to persist addresses
        User userProfile = userRepository.findById(userId)
                .orElseGet(() -> createNewUser(userId));

        return toUserResponse(userProfile, jwt);
    }

    private User createNewUser(UUID userId) {
        User newUser = new User();
        newUser.setId(userId);
        newUser.setAddresses(Collections.emptyList());
        return userRepository.save(newUser);
    }

    private UserResponse toUserResponse(User user, Jwt jwt) {
        return UserResponse.builder()
                .id(user.getId())
                .name(jwt.getClaim("name")) // from jwt
                .email(jwt.getClaim("email")) // from jwt
                .profileImageUrl(jwt.getClaim("picture")) // from jwt
                .addresses(user.getAddresses()) // from db
                .build();
    }
}