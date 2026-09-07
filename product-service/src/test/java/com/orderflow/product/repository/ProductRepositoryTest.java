package com.orderflow.product.repository;

import com.orderflow.product.domain.Product;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ProductRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("product_db")
            .withUsername("orderflow")
            .withPassword("orderflow");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private ProductRepository productRepository;

    @Test
    void findByIdAndActiveTrue_returnsProduct_whenActive() {
        Product saved = productRepository.save(Product.create("Widget", "desc", new BigDecimal("9.99"), 10));

        Optional<Product> found = productRepository.findByIdAndActiveTrue(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Widget");
        assertThat(found.get().getCreatedAt()).isNotNull();
        assertThat(found.get().getUpdatedAt()).isNotNull();
    }

    @Test
    void findByIdAndActiveTrue_returnsEmpty_whenInactive() {
        Product saved = productRepository.save(Product.create("Widget", "desc", new BigDecimal("9.99"), 10));
        saved.setActive(false);
        productRepository.saveAndFlush(saved);

        Optional<Product> found = productRepository.findByIdAndActiveTrue(saved.getId());

        assertThat(found).isEmpty();
    }

    @Test
    void findByIdAndActiveTrue_returnsEmpty_whenIdUnknown() {
        assertThat(productRepository.findByIdAndActiveTrue(UUID.randomUUID())).isEmpty();
    }

    @Test
    void findAllByActiveTrue_excludesInactiveAndPaginates() {
        productRepository.save(Product.create("Active One", "d", new BigDecimal("1.00"), 1));
        productRepository.save(Product.create("Active Two", "d", new BigDecimal("2.00"), 2));
        Product inactive = productRepository.save(Product.create("Inactive", "d", new BigDecimal("3.00"), 3));
        inactive.setActive(false);
        productRepository.saveAndFlush(inactive);

        Page<Product> page = productRepository.findAllByActiveTrue(PageRequest.of(0, 1));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(1);
    }
}
