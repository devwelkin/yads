package com.yads.storeservice.mapper;

import com.yads.storeservice.dto.CategoryRequest;
import com.yads.storeservice.dto.CategoryResponse;
import com.yads.storeservice.model.Category;
import org.mapstruct.*;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        uses = {ProductMapper.class}
)
public interface CategoryMapper {

    /**
     * Converts the Category entity to a CategoryResponse DTO.
     * Directly maps the ID from the associated Store object to the 'storeId' field.
     */
    @Mapping(source = "store.id",target = "storeId")
    CategoryResponse toCategoryResponse(Category category);

    /**
     * Creates a new Category entity from a CategoryRequest DTO.
     * The ID is ignored bc it will be generated upon persistence.
     * Store and products are ignored; relationships MUST be handled in the service layer,
     * preventing the mapper from performing ID-to-entity resolution.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "store", ignore = true)
    @Mapping(target = "products", ignore = true)
    Category toCategory(CategoryRequest request);

    /**
     * Updates an existing Category entity with data from the DTO.
     * ID, Store, and Products are ignored to prevent overwriting persistence/relationship
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "store", ignore = true)
    @Mapping(target = "products", ignore = true)
    void updateCategoryFromRequest(CategoryRequest request, @MappingTarget Category category);

}
