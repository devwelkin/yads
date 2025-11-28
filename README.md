# YADS (Yet Another Delivery System)

## Production Readiness Checklist

### Monitoring & Alerting
- [ ] **DLQ Monitoring (Mezarlık Bekçisi)**:
  - `q.dlq` kuyruğuna düşen mesajlar için mutlaka alarm kurulmalı (Grafana/Prometheus).
  - Bu kuyruktaki mesajlar manuel müdahale gerektirir. İzlenmezse veri kaybı veya tutarsızlık oluşabilir.

## Smoke Test Notes

- **Date:** 2025-11-27
- **Quick summary:** Performed end-to-end smoke tests across `order-service`, `store-service`, `courier-service`, and `notification-service`. The main happy path (create order → accept → stock reservation → courier assignment → pickup → deliver) executed successfully using the outbox + event-driven sagas.

- **What I verified:**
  - Order creation and `order.created` outbox publishing
  - Store accepted the order and `order.stock_reservation.requested` → `order.stock_reserved` flow
  - Stock decreased correctly (e.g., product `hdmi cable` stock updated)
  - `order.preparing` triggered courier-service assignment; nearest AVAILABLE courier was chosen and marked BUSY
  - `courier.assigned` returned to order-service, order updated with `courierId` and `order.assigned` published
  - Courier pickup (`order.on_the_way`) and delivery (`order.delivered`) flows executed, and notifications were delivered to the WebSocket HTML page

- **Known/accepted limitations (notes):**
  - After `order.delivered` or an order `cancelled`, courier remains `BUSY` in the current implementation. There is a status endpoint and manual update available; automatically resetting courier to `AVAILABLE` on `order.delivered`/`order.cancelled` is a suggested improvement.
  - Failure scenarios like `order.stock_reservation_failed` and courier-assignment-failure were not exhaustively tested in this smoke run and are recommended for further tests.

- **How to reproduce (quick):**
  1. Ensure at least one courier exists and is `AVAILABLE` with coordinates (`GET /api/v1/couriers/me` then `PATCH /api/v1/couriers/me/status` and `/me/location`).
  2. Create an order (customer token). Note the order id.
  3. Accept the order as store owner (token with `store_id` claim): `POST /api/orders/{orderId}/accept`.
  4. Watch logs for `order.stock_reservation.requested`, `order.stock_reserved`, `order.preparing`, `courier.assigned`, `order.on_the_way`, `order.delivered`.

- **CV bullets you can copy:**
  - Implemented an event-driven microservice demo using RabbitMQ, Outbox pattern and Saga pattern to ensure reliable async workflows (stock reservation, courier assignment, notifications).
  - Built idempotent subscribers and used transactional Outbox to guarantee at-least-once delivery and eventual consistency.
