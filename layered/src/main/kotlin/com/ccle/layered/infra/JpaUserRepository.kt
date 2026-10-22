package com.ccle.layered.infra

import com.ccle.layered.domain.GradePeriod
import com.ccle.layered.domain.GradeReview
import com.ccle.layered.domain.User
import com.ccle.layered.domain.UserRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 등급 재산정의 기준 금액(ADR-0004 §8.5, 별칭 t = users): 사용자 t가 구매자인 항목 중 CONFIRMED·DISPUTED이고
 * confirmed_at ∈ [:from, :to)의 체결가 합. 등급 경계(VIP ≥ 5,000,000 · GOLD ≥ 2,000,000 · SILVER ≥ 500,000 · 그 밖 BRONZE)는
 * gradeSql 한 곳에만 둔다 — 배치 대상 조건(KeysetBatchTargets)과 잠근 뒤의 재판정이 같은 식을 쓴다.
 */
internal val gradeBasisSql =
    "(SELECT coalesce(sum(i.price), 0) FROM order_items i JOIN orders o ON o.id = i.order_id" +
        " WHERE o.buyer_id = t.id AND i.status IN ('CONFIRMED', 'DISPUTED') AND i.confirmed_at >= :from AND i.confirmed_at < :to)"
internal val gradeSql =
    "(SELECT CASE WHEN b.v >= 5000000 THEN 'VIP' WHEN b.v >= 2000000 THEN 'GOLD' WHEN b.v >= 500000 THEN 'SILVER' ELSE 'BRONZE' END" +
        " FROM (SELECT $gradeBasisSql AS v) b)"

/** 기준일(:d) 등급 이력이 아직 없다(별칭 t = users). */
internal const val noGradeHistoryAtD = "NOT EXISTS (SELECT 1 FROM user_grade_history h WHERE h.user_id = t.id AND h.effective_from = :d)"

/** 등급 재산정 SQL의 파라미터(:d·:from·:to). */
internal fun gradeParams(period: GradePeriod): Map<String, Any> =
    mapOf("d" to period.effectiveFrom, "from" to period.from, "to" to period.to)

// 조회는 em.find·em.createQuery로 한다(Spring Data 저장소 메서드는 트랜잭션 밖에서 부르면 호출마다 읽기 전용 트랜잭션을 연다.
// god처럼 트랜잭션 밖에서는 autocommit 1문, 안에서는 그 트랜잭션의 영속성 컨텍스트로 읽는다).
@Component
class JpaUserRepository(private val em: EntityManager) : UserRepository {
    override fun find(id: Long): User? = em.find(User::class.java, id)

    override fun lock(id: Long) {
        em.createNativeQuery("SELECT id FROM users WHERE id = :id FOR UPDATE").setParameter("id", id).resultList
    }

    override fun reload(user: User) = em.refresh(user)

    override fun gradeOn(userId: Long, date: LocalDate): String =
        em.createNativeQuery(
            """
            SELECT coalesce(
                       (SELECT h.grade FROM user_grade_history h
                         WHERE h.user_id = u.id AND h.effective_from <= :orderDate
                         ORDER BY h.effective_from DESC LIMIT 1),
                       (SELECT 'BRONZE' FROM user_grade_history h WHERE h.user_id = u.id LIMIT 1),
                       u.grade)
              FROM users u
             WHERE u.id = :user
            """,
        ).setParameter("orderDate", date)
            .setParameter("user", userId)
            .singleResult as String

    override fun gradeReview(userId: Long, period: GradePeriod): GradeReview {
        val query = em.createNativeQuery(
            "SELECT $gradeSql, $gradeBasisSql, t.grade, $noGradeHistoryAtD FROM users t WHERE t.id = :user",
        ).setParameter("user", userId)
        gradeParams(period).forEach { (name, value) -> query.setParameter(name, value) }
        val row = query.singleResult as Array<*>
        return GradeReview(
            grade = row[0] as String,
            basis = (row[1] as Number).toLong(),
            currentGrade = row[2] as String,
            hasHistoryAtD = row[3] != true,
        )
    }

    override fun addGradeHistory(userId: Long, grade: String, period: GradePeriod, basis: Long) {
        em.createNativeQuery(
            "INSERT INTO user_grade_history (user_id, grade, effective_from, basis_amount) VALUES (:user, :grade, :d, :basis)",
        ).setParameter("user", userId).setParameter("grade", grade).setParameter("d", period.effectiveFrom).setParameter("basis", basis)
            .executeUpdate()
    }
}
