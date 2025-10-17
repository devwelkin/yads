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

        User user = userRepository.findById(userId)
                .map(existingUser -> {
                    // kullanıcı zaten var, bilgilerini jwt'den gelenlerle güncelleyebiliriz
                    existingUser.setName(jwt.getClaim("name"));
                    existingUser.setEmail(jwt.getClaim("email"));
                    return existingUser;
                })
                .orElseGet(() -> {
                    // kullanıcı yok, yenisini oluştur
                    User newUser = new User();
                    newUser.setId(userId);
                    newUser.setName(jwt.getClaim("name"));
                    newUser.setEmail(jwt.getClaim("email"));
                    // profil resmi ve adresler başlangıçta boş olabilir
                    newUser.setProfileImageUrl(jwt.getClaim("picture"));
                    newUser.setAddresses(Collections.emptyList());
                    return newUser;
                });

        User savedUser = userRepository.save(user);
        return toUserResponse(savedUser);
    }

    private UserResponse toUserResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .profileImageUrl(user.getProfileImageUrl())
                .addresses(user.getAddresses())
                .build();
    }
}
