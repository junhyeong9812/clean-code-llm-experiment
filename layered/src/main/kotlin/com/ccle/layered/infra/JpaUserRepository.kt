package com.ccle.layered.infra

import com.ccle.layered.domain.User
import com.ccle.layered.domain.UserRepository
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

interface UserJpaRepository : JpaRepository<User, Long>

@Repository
class JpaUserRepository(private val jpa: UserJpaRepository) : UserRepository {
    override fun find(id: Long): User? = jpa.findById(id).orElse(null)
}
