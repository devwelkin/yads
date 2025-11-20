package com.yads.courierservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "outbox")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private String aggregateType;

  @Column(nullable = false)
  private String aggregateId;

  @Column(nullable = false)
  private String type; // Event type (routing key)

  @Column(columnDefinition = "jsonb", nullable = false)
  @JdbcTypeCode(SqlTypes.JSON)
  private String payload;

  @Column(nullable = false)
  private LocalDateTime createdAt;

  @Column(nullable = false)
  private boolean processed;
}
