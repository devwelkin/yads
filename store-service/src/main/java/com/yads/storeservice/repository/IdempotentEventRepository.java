package com.yads.storeservice.repository;

import com.yads.storeservice.model.IdempotentEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IdempotentEventRepository extends JpaRepository<IdempotentEvent, String> {
}
