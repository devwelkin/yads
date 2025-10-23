// store-service/src/main/java/com/yads/storeservice/config/SecurityConfig.java
package com.yads.storeservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        // --- PUBLIC ENDPOINTS (GET Requests) ---
                        .requestMatchers(HttpMethod.GET, "/api/v1/stores/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/categories/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/products/**").permitAll()

                        // --- PROTECTED ENDPOINTS () ---
                        // store/category/product creation, update, deletion operations (POST, PATCH, DELETE)
                        // or user-specific endpoints like "my-stores" require authentication.
                        .requestMatchers("/api/v1/stores/**").authenticated()
                        .requestMatchers("/api/v1/categories/**").authenticated()
                        .requestMatchers("/api/v1/products/**").authenticated()

                        // rest of endpoints are protected
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(withDefaults()))
                .csrf(csrf -> csrf.disable());

        return http.build();
    }
}