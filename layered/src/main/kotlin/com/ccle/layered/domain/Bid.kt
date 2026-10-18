package com.ccle.layered.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "product_sizes")
class ProductSize(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var productId: Long,
    var size: String,
)

@Entity
@Table(name = "bids")
class Bid(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var userId: Long,
    var productSizeId: Long,
    var side: String,
    var price: Long,
    var status: String,
    var expiresAt: Instant,
    var createdAt: Instant,
)

@Entity
@Table(name = "bid_events")
class BidEvent(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var bidId: Long,
    var fromStatus: String? = null,
    var toStatus: String,
    var tradeId: Long? = null,
    var createdAt: Instant,
)
