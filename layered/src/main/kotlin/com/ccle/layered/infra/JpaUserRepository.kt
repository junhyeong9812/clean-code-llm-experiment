package com.ccle.layered.infra

import com.ccle.layered.domain.User
import com.ccle.layered.domain.UserRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository

// 조회는 em.find·em.createQuery로 한다(Spring Data 저장소 메서드는 트랜잭션 밖에서 부르면 호출마다 읽기 전용 트랜잭션을 연다.
// god처럼 트랜잭션 밖에서는 autocommit 1문, 안에서는 그 트랜잭션의 영속성 컨텍스트로 읽는다).
@Repository
class JpaUserRepository(private val em: EntityManager) : UserRepository {
    override fun find(id: Long): User? = em.find(User::class.java, id)

    override fun lock(id: Long) {
        em.createNativeQuery("SELECT id FROM users WHERE id = :id FOR UPDATE").setParameter("id", id).resultList
    }

    override fun reload(user: User) = em.refresh(user)
}
