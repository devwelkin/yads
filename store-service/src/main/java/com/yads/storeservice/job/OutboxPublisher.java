package com.yads.storeservice.job;

import com.yads.storeservice.model.OutboxEvent;
import com.yads.storeservice.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

  private final OutboxRepository outboxRepository;
  private final RabbitTemplate rabbitTemplate;

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
        String exchange = event.getType().startsWith("product.")
            ? "store_events_exchange"
            : "order_events_exchange";

        // 1. Read payload as raw JSON string (do NOT deserialize)
        String jsonPayload = event.getPayload();

        // 2. Prepare message headers
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);

        // 3. Whisper the real class type to the consumer via header
        if (event.getType().startsWith("product.") && !"product.deleted".equals(event.getType())) {
          props.setHeader("__TypeId__", "com.yads.common.contracts.ProductEventDto");
        } else if ("product.deleted".equals(event.getType())) {
          props.setHeader("__TypeId__", "java.util.UUID");
        }
        // No need to set __TypeId__ for order.* events — consumers can handle Object

        // 4. Send the message
        Message message = new Message(jsonPayload.getBytes(StandardCharsets.UTF_8), props);
        rabbitTemplate.send(exchange, event.getType(), message);

        // 5. Mark event as processed
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
