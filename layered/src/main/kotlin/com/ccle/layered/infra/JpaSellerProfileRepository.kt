package com.ccle.layered.infra

import com.ccle.layered.domain.SellerProfile
import com.ccle.layered.domain.SellerProfileRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component

@Component
class JpaSellerProfileRepository(private val em: EntityManager) : SellerProfileRepository {

    override fun find(userId: Long): SellerProfile? = em.find(SellerProfile::class.java, userId)

    override fun add(profile: SellerProfile) = em.persist(profile)
}
