package com.orderflow.inventory.controller;

import com.orderflow.inventory.dto.AdjustStockRequest;
import com.orderflow.inventory.dto.CreateStockRequest;
import com.orderflow.inventory.dto.StockResponse;
import com.orderflow.inventory.service.InventoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/{productId}")
    public ResponseEntity<StockResponse> getStock(@PathVariable UUID productId) {
        return ResponseEntity.ok(inventoryService.getStock(productId));
    }

    @PostMapping
    public ResponseEntity<StockResponse> createStock(@Valid @RequestBody CreateStockRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inventoryService.createStock(request));
    }

    @PutMapping("/{productId}/adjust")
    public ResponseEntity<StockResponse> adjustStock(@PathVariable UUID productId, @Valid @RequestBody AdjustStockRequest request) {
        return ResponseEntity.ok(inventoryService.adjustStock(productId, request.adjustment()));
    }
}
