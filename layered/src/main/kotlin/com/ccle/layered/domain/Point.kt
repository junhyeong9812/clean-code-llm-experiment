package com.ccle.layered.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "point_ledger")
class PointLedgerEntry(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var userId: Long,
    var kind: String,
    var status: String,
    var amount: Long,
    var remaining: Long? = null,
    var expiresAt: Instant? = null,
    var orderItemId: Long? = null,
    var orderId: Long? = null,
    var createdAt: Instant,
)

@Entity
@Table(name = "point_allocations")
class PointAllocation(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var entryId: Long,
    var earnEntryId: Long,
    var amount: Long,
)

@Entity
@Table(name = "point_balances")
class PointBalance(
    @Id
    var userId: Long,
    var available: Long,
    var pending: Long,
    var updatedAt: Instant,
)
