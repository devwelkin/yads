# YADS (Yet Another Delivery System)

## Production Readiness Checklist

### Monitoring & Alerting
- [ ] **DLQ Monitoring (Mezarlık Bekçisi)**:
  - `q.dlq` kuyruğuna düşen mesajlar için mutlaka alarm kurulmalı (Grafana/Prometheus).
  - Bu kuyruktaki mesajlar manuel müdahale gerektirir. İzlenmezse veri kaybı veya tutarsızlık oluşabilir.
