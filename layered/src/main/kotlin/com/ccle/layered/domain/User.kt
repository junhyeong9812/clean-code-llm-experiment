package com.ccle.layered.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

// 도메인 객체 = JPA 엔티티(1단계가 쓰는 테이블의 행). 스키마는 docker/schema/tables.sql 이 정본이고 앱은 validate만 한다.
// 연관(@ManyToOne 등) 없이 외래 키는 id 값 그대로 둔다. 상태·종류 같은 enum 값은 DB와 같은 문자열로 둔다.
// 상태 전이·금액 계산·검증은 이 클래스들의 메서드로 둔다.

@Entity
@Table(name = "users")
class User(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var email: String,
    var name: String,
    var grade: String,
    var purchaseRestrictedUntil: Instant? = null,
    var saleRestrictedUntil: Instant? = null,
    var createdAt: Instant,
)

interface UserRepository {
    fun find(id: Long): User?
}
