package com.yads.storeservice.mapper;


import com.yads.storeservice.dto.ProductRequest;
import com.yads.storeservice.dto.ProductResponse;
import com.yads.storeservice.model.Product;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface ProductMapper {

    /**
     * Converts the Product entity to a ProductResponse DTO.
     * Maps the ID of the 'category' object to the 'categoryId' field.
     */
    @Mapping(source = "category.id", target = "categoryId")
    ProductResponse toProductResponse(Product product);

    /**
     * Creates a new Product entity from a ProductRequest DTO.
     * The 'category' field is intentionally ignored because the conversion
     * from ID to entity performed in the service layer.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "category", ignore = true)
    @Mapping(target = "isAvailable", ignore = true)
    Product toProduct(ProductRequest request);

    /**
     * Updates an existing Product entity with data from the ProductRequest DTO.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "category", ignore = true)
    @Mapping(target = "isAvailable", ignore = true)
    void updateProductFromRequest(ProductRequest request, @MappingTarget Product product);
}
