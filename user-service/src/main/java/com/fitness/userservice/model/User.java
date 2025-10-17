package com.fitness.userservice.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "users")
@Data

public class User {

    @Id // This IS the Keycloak subject ID ('sub' claim)
    private UUID id;

    // Synced from Keycloak JWT
    @Column(nullable = false)
    private String name;

    // Synced from Keycloak JWT
    @Column(nullable = false, unique = true)
    private String email;

    private String profileImageUrl; // Managed by the user

    // The user has many addresses. when a user is deleted, their addresses go too.
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "user_addresses", joinColumns = @JoinColumn(name = "user_id"))
    private List<Address> addresses; //

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

}
