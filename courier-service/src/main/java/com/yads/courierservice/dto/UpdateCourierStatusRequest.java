package com.yads.courierservice.dto;

import com.yads.courierservice.model.CourierStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateCourierStatusRequest {
  @NotNull(message = "Status is required")
  private CourierStatus status;
}
