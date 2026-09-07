package com.orderflow.product.service;

import com.orderflow.common.error.ResourceNotFoundException;
import com.orderflow.product.domain.Product;
import com.orderflow.product.dto.CreateProductRequest;
import com.orderflow.product.dto.ProductResponse;
import com.orderflow.product.dto.UpdateProductRequest;
import com.orderflow.product.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductService productService;

    @Test
    void getById_returnsProduct_whenActiveAndPresent() {
        Product product = Product.create("Widget", "A widget", new BigDecimal("9.99"), 10);
        when(productRepository.findByIdAndActiveTrue(product.getId())).thenReturn(Optional.of(product));

        ProductResponse response = productService.getById(product.getId());

        assertThat(response.name()).isEqualTo("Widget");
        assertThat(response.price()).isEqualByComparingTo("9.99");
        assertThat(response.stockQuantity()).isEqualTo(10);
    }

    @Test
    void getById_throwsNotFound_whenAbsent() {
        UUID id = UUID.randomUUID();
        when(productRepository.findByIdAndActiveTrue(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.getById(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void list_mapsPageOfEntitiesToPageOfResponses() {
        Product product = Product.create("Widget", "A widget", new BigDecimal("9.99"), 10);
        Pageable pageable = PageRequest.of(0, 20);
        Page<Product> page = new PageImpl<>(List.of(product), pageable, 1);
        when(productRepository.findAllByActiveTrue(pageable)).thenReturn(page);

        Page<ProductResponse> result = productService.list(pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).name()).isEqualTo("Widget");
    }

    @Test
    void create_savesNewProductWithGivenFields() {
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        CreateProductRequest request = new CreateProductRequest("Gadget", "A gadget", new BigDecimal("19.99"), 5);

        ProductResponse response = productService.create(request);

        assertThat(response.name()).isEqualTo("Gadget");
        assertThat(response.price()).isEqualByComparingTo("19.99");
        assertThat(response.stockQuantity()).isEqualTo(5);
    }

    @Test
    void update_mutatesExistingProduct() {
        Product product = Product.create("Widget", "A widget", new BigDecimal("9.99"), 10);
        when(productRepository.findByIdAndActiveTrue(product.getId())).thenReturn(Optional.of(product));
        UpdateProductRequest request = new UpdateProductRequest("Widget Pro", "Better widget", new BigDecimal("14.99"), 20);

        ProductResponse response = productService.update(product.getId(), request);

        assertThat(response.name()).isEqualTo("Widget Pro");
        assertThat(response.price()).isEqualByComparingTo("14.99");
        assertThat(response.stockQuantity()).isEqualTo(20);
    }

    @Test
    void update_throwsNotFound_whenAbsent() {
        UUID id = UUID.randomUUID();
        when(productRepository.findByIdAndActiveTrue(id)).thenReturn(Optional.empty());
        UpdateProductRequest request = new UpdateProductRequest("Widget Pro", "Better widget", new BigDecimal("14.99"), 20);

        assertThatThrownBy(() -> productService.update(id, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void delete_softDeletesByClearingActiveFlag() {
        Product product = Product.create("Widget", "A widget", new BigDecimal("9.99"), 10);
        when(productRepository.findByIdAndActiveTrue(product.getId())).thenReturn(Optional.of(product));

        productService.delete(product.getId());

        assertThat(product.isActive()).isFalse();
    }

    @Test
    void delete_throwsNotFound_whenAbsent() {
        UUID id = UUID.randomUUID();
        when(productRepository.findByIdAndActiveTrue(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.delete(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
