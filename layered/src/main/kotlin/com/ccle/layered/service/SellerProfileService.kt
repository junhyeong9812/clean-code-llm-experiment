package com.ccle.layered.service

import com.ccle.layered.domain.MarketClock
import com.ccle.layered.domain.SellerProfile
import com.ccle.layered.domain.SellerProfileInput
import com.ccle.layered.domain.SellerProfileRead
import com.ccle.layered.domain.SellerProfileRepository
import com.ccle.layered.domain.UserRepository
import com.ccle.layered.domain.notFound
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate

/** A10 판매자 프로필 저장·조회(ADR-0007 §12.1). 사용자 확인(401)은 부르는 쪽이 한다. */
@Service
class SellerProfileService(
    private val clock: MarketClock,
    private val tx: TransactionTemplate,
    private val users: UserRepository,
    private val profiles: SellerProfileRepository,
) {
    /** 저장: 400 → 사용자 행 FOR UPDATE → 없으면 INSERT(created_at), 있으면 UPDATE. updated_at = 현재. */
    fun put(userId: Long, shopName: String?, intro: String?, vacationFrom: LocalDate?, vacationTo: LocalDate?): SellerProfileRead {
        val input = SellerProfileInput.of(shopName, intro, vacationFrom, vacationTo)
        val now = clock.now()
        return tx.execute {
            users.lock(userId)
            val profile = profiles.find(userId)
            if (profile == null) {
                val created = SellerProfile.create(userId, input, now)
                profiles.add(created)
                SellerProfileRead(created, now)
            } else {
                profile.update(input, now)
                SellerProfileRead(profile, now)
            }
        }!!
    }

    /** 조회(헤더 불필요): 사용자나 프로필이 없으면 404. */
    fun profile(userId: Long): SellerProfileRead {
        users.find(userId) ?: notFound("없는 사용자: $userId")
        val profile = profiles.find(userId) ?: notFound("프로필이 없는 사용자: $userId")
        return SellerProfileRead(profile, clock.now())
    }
}
