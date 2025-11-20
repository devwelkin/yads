package com.yads.courierservice.repository;

import com.yads.courierservice.model.IdempotentEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IdempotentEventRepository extends JpaRepository<IdempotentEvent, String> {
}
