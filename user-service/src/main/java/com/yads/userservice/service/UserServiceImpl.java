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

        // find the user profile in our db.
        // if it doesn't exist (e.g., first login), create a new, empty profile
        // and save it. this ensures we always have a record to attach addresses to.
        User userProfile = userRepository.findById(userId)
                .orElseGet(() -> {
                    // user yok, yenisini oluştur (sadece id ve boş adres listesiyle)
                    User newUser = new User();
                    newUser.setId(userId);
                    newUser.setAddresses(Collections.emptyList());
                    return userRepository.save(newUser); // save the new, minimal profile
                });

        // now, assemble the response using data from *both*
        // the db profile (for addresses) and the live jwt (for everything else)
        return toUserResponse(userProfile, jwt);
    }

    // updated signature: requires jwt
    private UserResponse toUserResponse(User user, Jwt jwt) {
        return UserResponse.builder()
                .id(user.getId())
                .name(jwt.getClaim("name")) // from jwt
                .email(jwt.getClaim("email")) // from jwt
                .profileImageUrl(jwt.getClaim("picture")) // from jwt
                .addresses(user.getAddresses()) // from our db
                .build();
    }
}