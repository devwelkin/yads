package com.yads.storeservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "idempotent_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdempotentEvent {

  @Id
  @Column(name = "event_key", nullable = false)
  private String eventKey; // e.g., "ORDER_RESERVE_STOCK:<orderId>"

  @Column(nullable = false)
  private LocalDateTime createdAt;
}
