package com.yads.courierservice;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Abstract base class for integration tests using TestContainers.
 * Provides PostgreSQL and RabbitMQ containers that are shared across all
 * integration tests.
 *
 * Pattern borrowed from order-service and store-service best practices:
 * - Single container instance per test suite (performance optimization)
 * - Dynamic property injection for Spring context
 * - Random port for web environment (parallel test execution)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

  // Shared containers across all tests (started once, reused for all tests)
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.11-management-alpine");

  static {
    POSTGRES.start();
    RABBIT.start();
  }

  /**
   * Inject container URLs into Spring context dynamically.
   * This overrides application.yml properties with TestContainer URLs.
   */
  @DynamicPropertySource
  static void dynamicProperties(DynamicPropertyRegistry registry) {
    // PostgreSQL properties
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);

    // RabbitMQ properties
    registry.add("spring.rabbitmq.host", RABBIT::getHost);
    registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
    registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
    registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
  }
}
