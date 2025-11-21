package com.yads.storeservice;

import com.yads.storeservice.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Test helper service to wrap repository calls in transactions.
 * This is needed because @Modifying queries in JPA require a transaction
 * context.
 */
@Service
public class TestProductService {

  @Autowired
  private ProductRepository productRepository;

  @Transactional
  public int decreaseStock(UUID productId, int quantity) {
    return productRepository.decreaseStock(productId, quantity);
  }
}
