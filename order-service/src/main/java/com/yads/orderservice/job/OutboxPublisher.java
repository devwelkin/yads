package com.yads.orderservice.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yads.orderservice.model.OutboxEvent;
import com.yads.orderservice.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

  private final OutboxRepository outboxRepository;
  private final RabbitTemplate rabbitTemplate;
  private final ObjectMapper objectMapper;

  @Scheduled(fixedDelay = 2000)
  @Transactional
  public void publishOutboxEvents() {
    List<OutboxEvent> events = outboxRepository.findTop50ByProcessedFalseOrderByCreatedAtAsc();

    if (events.isEmpty()) {
      return;
    }

    log.debug("Found {} outbox events to publish", events.size());

    for (OutboxEvent event : events) {
      try {
        Object payloadObj = objectMapper.readValue(event.getPayload(), Object.class);
        rabbitTemplate.convertAndSend("order_events_exchange", event.getType(), payloadObj);

        event.setProcessed(true);
        outboxRepository.save(event);

        log.info("Published outbox event: id={}, type={}", event.getId(), event.getType());

      } catch (Exception e) {
        log.error("Failed to publish outbox event: id={}", event.getId(), e);
      }
    }
  }

  @Scheduled(cron = "0 0 3 * * *")
  @Transactional
  public void cleanupProcessedEvents() {
    LocalDateTime cutoff = LocalDateTime.now().minusDays(1);
    log.info("Starting cleanup of processed outbox events older than {}", cutoff);

    int totalDeleted = 0;
    while (true) {
      List<OutboxEvent> batch = outboxRepository.findTop1000ByProcessedTrueAndCreatedAtBefore(cutoff);
      if (batch.isEmpty()) {
        break;
      }
      outboxRepository.deleteAll(batch);
      totalDeleted += batch.size();
      log.debug("Deleted batch of {} processed events", batch.size());
    }

    log.info("Cleanup completed. Total deleted: {}", totalDeleted);
  }
}
