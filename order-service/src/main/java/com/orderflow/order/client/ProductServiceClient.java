package com.orderflow.order.client;

import com.orderflow.order.exception.ProductNotFoundException;
import com.orderflow.order.exception.UpstreamServiceException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;

@Component
public class ProductServiceClient {

    private final RestClient restClient;

    public ProductServiceClient(RestClient productServiceRestClient) {
        this.restClient = productServiceRestClient;
    }

    public ProductDto getProduct(UUID productId) {
        try {
            return restClient.get()
                    .uri("/api/v1/products/{id}", productId)
                    .retrieve()
                    .body(ProductDto.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new ProductNotFoundException("Product " + productId + " not found");
        } catch (RestClientException e) {
            throw new UpstreamServiceException("Product Service call failed for product " + productId, e);
        }
    }
}
