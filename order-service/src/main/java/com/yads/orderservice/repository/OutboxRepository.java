package com.yads.orderservice.repository;

import com.yads.orderservice.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {
  List<OutboxEvent> findTop50ByProcessedFalseOrderByCreatedAtAsc();

  List<OutboxEvent> findTop1000ByProcessedTrueAndCreatedAtBefore(LocalDateTime cutoff);
}
