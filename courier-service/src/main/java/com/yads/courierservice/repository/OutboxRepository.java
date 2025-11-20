package com.yads.courierservice.repository;

import com.yads.courierservice.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {
  // Fetch only the oldest 50 unprocessed events to avoid memory issues
  List<OutboxEvent> findTop50ByProcessedFalseOrderByCreatedAtAsc();

  // Fetch processed events for batch deletion
  List<OutboxEvent> findTop1000ByProcessedTrueAndCreatedAtBefore(LocalDateTime cutoff);
}
