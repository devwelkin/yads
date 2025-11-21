package com.yads.orderservice;

import com.yads.common.model.Address;
import com.yads.orderservice.dto.OrderItemRequest;
import com.yads.orderservice.dto.OrderRequest;
import com.yads.orderservice.model.*;
import com.yads.orderservice.repository.OrderRepository;
import com.yads.orderservice.repository.OutboxRepository;
import com.yads.orderservice.repository.ProductSnapshotRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
public class CreateOrderIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private ProductSnapshotRepository productRepository;

    @AfterEach
    void tearDown() {
        orderRepository.deleteAll();
        outboxRepository.deleteAll();
        productRepository.deleteAll();
    }

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID PROD_ID = UUID.randomUUID();

    @Test
    void should_create_order_successfully_from_local_snapshot() throws Exception {

        // 1. ARRANGE
        // Builder yok, artik new ve setter var.
        // Bu kodun cirkinligi, gercegin ciplakligidir.
        var existingProduct = new ProductSnapshot();
        existingProduct.setProductId(PROD_ID);
        existingProduct.setStoreId(STORE_ID);
        existingProduct.setName("sisyphus boulder polishing kit");
        existingProduct.setPrice(BigDecimal.valueOf(100.0));
        existingProduct.setStock(50);
        existingProduct.setAvailable(true);

        productRepository.save(existingProduct);

        // Address objesi
        var address = new Address();
        address.setStreet("infinite loop 1");
        address.setCity("cupertino");
        address.setPostalCode("95014");
        address.setCountry("USA");

        // Item Request
        var itemReq = new OrderItemRequest();
        itemReq.setProductId(PROD_ID);
        itemReq.setQuantity(1);

        // Main Request
        var request = new OrderRequest();
        request.setStoreId(STORE_ID);
        request.setShippingAddress(address);
        request.setItems(List.of(itemReq));

        // 2. ACT
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().jwt(builder -> builder
                                .subject(UUID.randomUUID().toString()) // <--- give it what it wants
                                .claim("email", "dev@welkin.com"))))
                .andExpect(status().isCreated());

        // 3. ASSERT
        var orders = orderRepository.findAll();
        assertEquals(1, orders.size());
        assertEquals("PENDING", orders.getFirst().getStatus().toString());

        // bigdecimal karsilastirmasi boyle yapilir, equals kullanma (scale farki olabilir)
        // 100.00 ile 100.0 matematikte esittir ama java equals'da degildir.
        // compareTo == 0 en guvenlisidir.
        assertEquals(0, orders.getFirst().getTotalPrice().compareTo(BigDecimal.valueOf(100.0)));

        var updatedProduct = productRepository.findById(PROD_ID).orElseThrow();
        assertEquals(50, updatedProduct.getStock());

        var outboxEvents = outboxRepository.findAll();
        assertEquals(1, outboxEvents.size());
    }

    @Test
    void should_initiate_stock_reservation_when_store_owner_accepts() throws Exception {
        // 1. ARRANGE
        // Önce db'de PENDING statüsünde bir sipariş oluşturmamız lazım.
        // Bunu repository ile manuel yapıyoruz, controller ile değil.

        UUID storeId = UUID.randomUUID();
        UUID userId = UUID.randomUUID(); // müşteri

        Order pendingOrder = new Order();
        pendingOrder.setUserId(userId);
        pendingOrder.setStoreId(storeId);
        pendingOrder.setStatus(OrderStatus.PENDING); // ÖNEMLİ
        pendingOrder.setTotalPrice(BigDecimal.valueOf(250.0));

        // item ekle
        OrderItem item = new OrderItem();
        item.setProductId(UUID.randomUUID());
        item.setProductName("sisyphus boulder polish");
        item.setQuantity(5);
        item.setPrice(BigDecimal.valueOf(50.0));
        item.setOrder(pendingOrder);
        pendingOrder.setItems(List.of(item));

        // snapshot'a gerek yok çünkü acceptOrder snapshot'a bakmıyor, direkt order'a bakıyor.
        orderRepository.save(pendingOrder);

        // 2. ACT
        // Store Owner rolüyle ve store_id claim'iyle istek atıyoruz.
        // Keycloak yapını taklit ediyoruz.

        mockMvc.perform(post("/api/v1/orders/" + pendingOrder.getId() + "/accept")
                        .with(jwt().jwt(builder -> builder
                                .subject(UUID.randomUUID().toString()) // store owner user id
                                .claim("store_id", storeId.toString()) // <-- KRİTİK NOKTA
                                .claim("resource_access", Map.of(
                                        "yads-backend", Map.of(
                                                "roles", List.of("STORE_OWNER") // <-- ROL KONTROLÜ
                                        )
                                ))
                        )))
                .andExpect(status().isOk());

        // 3. ASSERT

        // A. Order statüsü değişti mi?
        Order updatedOrder = orderRepository.findById(pendingOrder.getId()).orElseThrow();
        assertEquals(OrderStatus.RESERVING_STOCK, updatedOrder.getStatus());

        // B. Outbox'a doğru event düştü mü?
        List<OutboxEvent> events = outboxRepository.findAll();

        // createOrder yapmadığımız için sadece 1 event (reservation request) olmalı
        // ama tearDown çalışmadıysa önceki testlerden kalanlar olabilir, sonuncuyu alalım.
        OutboxEvent reservationEvent = events.stream()
                .filter(e -> e.getType().equals("order.stock_reservation.requested"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Reservation event not found"));

        assertTrue(reservationEvent.getPayload().contains(item.getProductId().toString()));
        assertTrue(reservationEvent.getPayload().contains("\"quantity\": 5"));
    }
}