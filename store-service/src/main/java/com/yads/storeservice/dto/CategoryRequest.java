package com.yads.storeservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CategoryRequest {

    @NotBlank(message = "Category name cannot be blank")
    @Size(min = 2, max = 100)
    private String name;

    private String description;
}
