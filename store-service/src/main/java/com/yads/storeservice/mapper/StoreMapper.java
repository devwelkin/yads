package com.yads.storeservice.mapper;

import com.yads.common.dto.StoreResponse;
import com.yads.storeservice.dto.StoreRequest;
import com.yads.storeservice.model.Store;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring"
        , unmappedTargetPolicy = ReportingPolicy.IGNORE
        , nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface StoreMapper {
    /**
     * @param request the source dto
     * @return a new store entity
     */
    Store toStore(StoreRequest request);

    /**
     * @param store the source entity
     * @return a mapped StoreResponse dto
     */
    @Mapping(target = "storeType", expression = "java(store.getStoreType() != null ? store.getStoreType().name() : null)")
    @Mapping(source = "address.street", target = "street")
    @Mapping(source = "address.city", target = "city")
    @Mapping(source = "address.state", target = "state")
    @Mapping(source = "address.postalCode", target = "postalCode")
    @Mapping(source = "address.country", target = "country")
    @Mapping(source = "address.latitude", target = "latitude")
    @Mapping(source = "address.longitude", target = "longitude")
    StoreResponse toStoreResponse(Store store);

    /**
     * @param request the source dto with new data
     * @param store   the existing entity to be updated
     */
    void updateStoreFromRequest(StoreRequest request, @MappingTarget Store store);
}
