package com.ccle.layered.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "trades")
class Trade(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var buyBidId: Long,
    var sellBidId: Long,
    var buyerId: Long,
    var sellerId: Long,
    var productSizeId: Long,
    var price: Long,
    var status: String,
    var matchedAt: Instant,
    var paymentDueAt: Instant,
)

@Entity
@Table(name = "buyer_warnings")
class BuyerWarning(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var userId: Long,
    var tradeId: Long,
    var createdAt: Instant,
)
