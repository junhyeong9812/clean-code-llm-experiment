package com.ccle.layered.infra

import com.ccle.layered.domain.User
import com.ccle.layered.domain.UserRepository
import jakarta.persistence.EntityManager
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

interface UserJpaRepository : JpaRepository<User, Long>

@Repository
class JpaUserRepository(private val jpa: UserJpaRepository, private val em: EntityManager) : UserRepository {
    override fun find(id: Long): User? = jpa.findById(id).orElse(null)

    override fun lock(id: Long) {
        em.createNativeQuery("SELECT id FROM users WHERE id = :id FOR UPDATE").setParameter("id", id).resultList
    }
}
