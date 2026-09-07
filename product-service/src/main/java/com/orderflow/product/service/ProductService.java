package com.orderflow.product.service;

import com.orderflow.common.error.ResourceNotFoundException;
import com.orderflow.product.domain.Product;
import com.orderflow.product.dto.CreateProductRequest;
import com.orderflow.product.dto.ProductResponse;
import com.orderflow.product.dto.UpdateProductRequest;
import com.orderflow.product.repository.ProductRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "products", key = "#id")
    public ProductResponse getById(UUID id) {
        Product product = productRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product " + id + " not found"));
        return ProductResponse.from(product);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "productList", key = "#pageable.pageNumber + '-' + #pageable.pageSize + '-' + #pageable.sort.toString()")
    public Page<ProductResponse> list(Pageable pageable) {
        return productRepository.findAllByActiveTrue(pageable).map(ProductResponse::from);
    }

    @Transactional
    @CacheEvict(value = "productList", allEntries = true)
    public ProductResponse create(CreateProductRequest request) {
        Product product = Product.create(request.name(), request.description(), request.price(), request.stockQuantity());
        return ProductResponse.from(productRepository.save(product));
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "products", key = "#id"),
            @CacheEvict(value = "productList", allEntries = true)
    })
    public ProductResponse update(UUID id, UpdateProductRequest request) {
        Product product = productRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product " + id + " not found"));

        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStockQuantity(request.stockQuantity());

        return ProductResponse.from(product);
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "products", key = "#id"),
            @CacheEvict(value = "productList", allEntries = true)
    })
    public void delete(UUID id) {
        Product product = productRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product " + id + " not found"));
        product.setActive(false);
    }
}
