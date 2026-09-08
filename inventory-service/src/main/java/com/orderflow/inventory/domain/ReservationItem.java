package com.orderflow.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "reservation_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationItem {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private int quantity;

    public static ReservationItem of(Reservation reservation, UUID productId, int quantity) {
        ReservationItem item = new ReservationItem();
        item.id = UUID.randomUUID();
        item.reservation = reservation;
        item.productId = productId;
        item.quantity = quantity;
        return item;
    }
}
