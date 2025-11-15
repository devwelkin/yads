package com.yads.storeservice.services;

import com.yads.common.contracts.ProductEventDto;
import com.yads.common.dto.ReserveStockRequest;
import com.yads.storeservice.dto.ProductRequest;
import com.yads.storeservice.dto.ProductResponse;
import com.yads.common.exception.AccessDeniedException;
import com.yads.common.exception.DuplicateResourceException;
import com.yads.common.exception.InsufficientStockException;
import com.yads.common.exception.ResourceNotFoundException;
import com.yads.storeservice.mapper.ProductMapper;
import com.yads.storeservice.model.Category;
import com.yads.storeservice.model.Product;
import com.yads.storeservice.repository.CategoryRepository;
import com.yads.storeservice.repository.ProductRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductServiceImpl.class);

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductMapper productMapper;
    private final RabbitTemplate rabbitTemplate;
    private final TopicExchange storeEventsExchange;

    @Override
    @Transactional
    public ProductResponse createProduct(UUID categoryId, ProductRequest request, UUID ownerId) {
        // Find category
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));

        // Check if user owns the store that contains this category
        if (!category.getStore().getOwnerId().equals(ownerId)) {
            log.warn("Access denied: User {} attempted to add product to category {} in store {} owned by {}",
                    ownerId, categoryId, category.getStore().getId(), category.getStore().getOwnerId());
            throw new AccessDeniedException("You are not authorized to add products to this category");
        }

        // Map request to product entity
        Product product = productMapper.toProduct(request);
        product.setCategory(category);

        // Set availability based on stock (if stock > 0, make it available)
        product.setIsAvailable(request.getStock() != null && request.getStock() > 0);



        // Save and return
        Product savedProduct = productRepository.save(product);

        log.info("Product created: id={}, name='{}', categoryId={}, storeId={}, price={}, stock={}",
                savedProduct.getId(), savedProduct.getName(), categoryId,
                category.getStore().getId(), savedProduct.getPrice(), savedProduct.getStock());

        // Publish product creation event
        publishProductUpdate(savedProduct, "product.created");
        return productMapper.toProductResponse(savedProduct);
    }

    @Override
    @Transactional
    public ProductResponse updateProduct(UUID productId, ProductRequest request, UUID ownerId) {
        // Find product
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        // Check authorization
        if (!product.getCategory().getStore().getOwnerId().equals(ownerId)) {
            log.warn("Access denied: User {} attempted to update product {} in store {} owned by {}",
                    ownerId, productId, product.getCategory().getStore().getId(), product.getCategory().getStore().getOwnerId());
            throw new AccessDeniedException("You are not authorized to update this product");
        }

        // Update basic fields (name, description, price, stock, imageUrl)
        productMapper.updateProductFromRequest(request, product);

        // Save and return
        Product updatedProduct = productRepository.save(product);

        log.info("Product updated: id={}, name='{}', storeId={}, price={}, stock={}",
                productId, updatedProduct.getName(), updatedProduct.getCategory().getStore().getId(),
                updatedProduct.getPrice(), updatedProduct.getStock());

        // Publish product update event
        publishProductUpdate(updatedProduct, "product.updated");
        return productMapper.toProductResponse(updatedProduct);
    }

    @Override
    @Transactional
    public void deleteProduct(UUID productId, UUID ownerId) {
        // Find product
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        // Check authorization
        if (!product.getCategory().getStore().getOwnerId().equals(ownerId)) {
            log.warn("Access denied: User {} attempted to delete product {} from store {} owned by {}",
                    ownerId, productId, product.getCategory().getStore().getId(), product.getCategory().getStore().getOwnerId());
            throw new AccessDeniedException("You are not authorized to delete this product");
        }

        // Delete
        String productName = product.getName();
        UUID storeId = product.getCategory().getStore().getId();
        productRepository.delete(product);

        log.info("Product deleted: id={}, name='{}', storeId={}, owner={}", productId, productName, storeId, ownerId);

        // Publish product deletion event
        rabbitTemplate.convertAndSend(storeEventsExchange.getName(), "product.deleted", productId);
    }

    @Override
    @Transactional
    public ProductResponse getProductById(UUID productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        return productMapper.toProductResponse(product);
    }

    @Override
    @Transactional
    public List<ProductResponse> getProductsByCategory(UUID categoryId) {
        // Verify category exists
        if (!categoryRepository.existsById(categoryId)) {
            throw new ResourceNotFoundException("Category not found");
        }

        List<Product> products = productRepository.findByCategoryId(categoryId);
        return products.stream()
                .map(productMapper::toProductResponse)
                .toList();
    }

    @Override
    @Transactional
    public List<ProductResponse> getAvailableProductsByCategory(UUID categoryId) {
        // Verify category exists
        if (!categoryRepository.existsById(categoryId)) {
            throw new ResourceNotFoundException("Category not found");
        }

        List<Product> products = productRepository.findByCategoryIdAndIsAvailableTrue(categoryId);
        return products.stream()
                .map(productMapper::toProductResponse)
                .toList();
    }

    @Override
    @Transactional
    public List<ProductResponse> getProductsByStore(UUID storeId) {
        List<Product> products = productRepository.findByCategoryStoreId(storeId);
        return products.stream()
                .map(productMapper::toProductResponse)
                .toList();
    }

    @Override
    @Transactional
    public List<ProductResponse> searchProductsByName(String name) {
        List<Product> products = productRepository.findByNameContainingIgnoreCase(name);
        return products.stream()
                .map(productMapper::toProductResponse)
                .toList();
    }

    @Override
    @Transactional
    public ProductResponse updateStock(UUID productId, Integer quantity, UUID ownerId) {
        // Find product
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        // Check authorization
        if (!product.getCategory().getStore().getOwnerId().equals(ownerId)) {
            log.warn("Access denied: User {} attempted to update stock for product {} in store {} owned by {}",
                    ownerId, productId, product.getCategory().getStore().getId(), product.getCategory().getStore().getOwnerId());
            throw new AccessDeniedException("You are not authorized to update stock for this product");
        }

        // Update stock
        int oldStock = product.getStock();
        product.setStock(quantity);

        // Auto-update availability based on stock
        product.setIsAvailable(quantity > 0);

        // Save and return
        Product updatedProduct = productRepository.save(product);

        log.info("Product stock updated: id={}, name='{}', oldStock={}, newStock={}, available={}",
                productId, product.getName(), oldStock, quantity, quantity > 0);

        // Publish product update event
        publishProductUpdate(updatedProduct, "product.stock.updated");
        return productMapper.toProductResponse(updatedProduct);
    }

    @Override
    @Transactional
    public ProductResponse toggleAvailability(UUID productId, UUID ownerId) {
        // Find product
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        // Check authorization
        if (!product.getCategory().getStore().getOwnerId().equals(ownerId)) {
            log.warn("Access denied: User {} attempted to toggle availability for product {} in store {} owned by {}",
                    ownerId, productId, product.getCategory().getStore().getId(), product.getCategory().getStore().getOwnerId());
            throw new AccessDeniedException("You are not authorized to change availability for this product");
        }

        // Toggle availability
        boolean oldAvailability = product.getIsAvailable();
        product.setIsAvailable(!product.getIsAvailable());

        // Save and return
        Product updatedProduct = productRepository.save(product);

        log.info("Product availability toggled: id={}, name='{}', oldAvailability={}, newAvailability={}",
                productId, product.getName(), oldAvailability, !oldAvailability);

        // Publish product update event
        publishProductUpdate(updatedProduct, "product.availability.updated");
        return productMapper.toProductResponse(updatedProduct);
    }

    @Override
    @Transactional
    public ProductResponse reserveProduct(UUID productId, ReserveStockRequest request) {
        // Find product
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        // Store control
        if (!product.getCategory().getStore().getId().equals(request.getStoreId())) {
            log.warn("Invalid store: Product {} belongs to store {} but request specified store {}",
                    productId, product.getCategory().getStore().getId(), request.getStoreId());
            throw new IllegalArgumentException("Product does not belong to the specified store");
        }

        if (!product.getIsAvailable()) {
            log.info("Product {} is not available for reservation (requested by store {})",
                    productId, request.getStoreId());
            throw new InsufficientStockException("Product is not available");
        }

        if (product.getStock() < request.getQuantity()) {
            log.info("Insufficient stock for product {}: available={}, requested={}",
                    productId, product.getStock(), request.getQuantity());
            throw new InsufficientStockException(
                String.format("Not enough stock. Requested: %d", request.getQuantity())
            );
        }

        int oldStock = product.getStock();
        int newStock = product.getStock() - request.getQuantity();
        product.setStock(newStock);

        if (newStock == 0) {
            product.setIsAvailable(false);
        }

        Product savedProduct = productRepository.save(product);

        log.info("Product stock reserved: id={}, name='{}', reserved={}, oldStock={}, newStock={}, storeId={}",
                productId, product.getName(), request.getQuantity(), oldStock, newStock, request.getStoreId());

        publishProductUpdate(savedProduct, "product.stock.reserved");

        return productMapper.toProductResponse(savedProduct);
    }

    @Override
    @Transactional
    public void restoreStock(UUID productId, Integer quantity, UUID storeId) {
        log.info("Restoring stock: productId={}, quantity={}, storeId={}", productId, quantity, storeId);

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> {
                    log.warn("Product not found during stock restoration: productId={}", productId);
                    return new ResourceNotFoundException("Product not found");
                });

        // Store control (optional - for validation)
        if (!product.getCategory().getStore().getId().equals(storeId)) {
            log.warn("Invalid store during restoration: Product {} belongs to store {} but request specified store {}",
                    productId, product.getCategory().getStore().getId(), storeId);
            throw new IllegalArgumentException("Product does not belong to the specified store");
        }

        int oldStock = product.getStock();
        int newStock = oldStock + quantity;
        product.setStock(newStock);

        // If product was unavailable due to 0 stock, make it available again
        if (oldStock == 0 && newStock > 0) {
            product.setIsAvailable(true);
            log.info("Product availability restored: productId={}, name='{}'", productId, product.getName());
        }

        Product savedProduct = productRepository.save(product);

        log.info("Product stock restored: id={}, name='{}', restored={}, oldStock={}, newStock={}, storeId={}",
                productId, product.getName(), quantity, oldStock, newStock, storeId);

        publishProductUpdate(savedProduct, "product.stock.restored");
    }

    // Helper method for publishing product update events
    private void publishProductUpdate(Product product, String routingKey) {
        ProductEventDto eventDto = ProductEventDto.builder()
                .productId(product.getId())
                .name(product.getName())
                .price(product.getPrice())
                .stock(product.getStock())
                .isAvailable(product.getIsAvailable())
                .storeId(product.getCategory().getStore().getId())
                .build();

        rabbitTemplate.convertAndSend(storeEventsExchange.getName(), routingKey, eventDto);
    }
}
