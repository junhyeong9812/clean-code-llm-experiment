package com.ccle.layered.domain

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

// A10 판매자 프로필(ADR-0007 §12.1). 휴가는 표시만 한다 — 입찰·체결·발송 기한에 영향이 없다.

/** 프로필 저장 요청의 값(§12.1·§19의 14): shopName 1~40자, intro 0~500자(필수 문자열), 휴가는 둘 다 null이거나 둘 다 있고 from ≤ to. */
class SellerProfileInput private constructor(
    val shopName: String,
    val intro: String,
    val vacationFrom: LocalDate?,
    val vacationTo: LocalDate?,
) {
    companion object {
        fun of(shopName: String?, intro: String?, vacationFrom: LocalDate?, vacationTo: LocalDate?): SellerProfileInput {
            if (shopName == null) invalidRequest("shopName이 필요하다")
            if (textLength(shopName) !in 1..40) invalidRequest("shopName은 1~40자")
            if (intro == null) invalidRequest("intro가 필요하다")
            if (textLength(intro) > 500) invalidRequest("intro는 0~500자")
            if ((vacationFrom == null) != (vacationTo == null)) invalidRequest("vacationFrom·vacationTo는 둘 다 있거나 둘 다 null")
            if (vacationFrom != null && vacationFrom.isAfter(vacationTo)) invalidRequest("vacationFrom이 vacationTo보다 늦다")
            return SellerProfileInput(shopName, intro, vacationFrom, vacationTo)
        }
    }
}

/** 판매자 프로필 행(§3 seller_profiles, PK = user_id). */
@Entity
@Table(name = "seller_profiles")
class SellerProfile(
    @Id
    var userId: Long,
    shopName: String,
    intro: String,
    vacationFrom: LocalDate? = null,
    vacationTo: LocalDate? = null,
    var createdAt: Instant,
    updatedAt: Instant,
) {
    var shopName: String = shopName
        private set

    var intro: String = intro
        private set

    var vacationFrom: LocalDate? = vacationFrom
        private set

    var vacationTo: LocalDate? = vacationTo
        private set

    var updatedAt: Instant = updatedAt
        private set

    /** 갱신(§12.1): 값을 바꾸고 updated_at = 현재. */
    fun update(input: SellerProfileInput, now: Instant) {
        shopName = input.shopName
        intro = input.intro
        vacationFrom = input.vacationFrom
        vacationTo = input.vacationTo
        updatedAt = now
    }

    /** onVacation = 오늘(KST)이 [vacationFrom, vacationTo] 안(계산 값). */
    fun onVacation(now: Instant): Boolean {
        val today = LocalDate.ofInstant(now, KST)
        val from = vacationFrom
        val to = vacationTo
        return from != null && to != null && !today.isBefore(from) && !today.isAfter(to)
    }

    companion object {
        /** 첫 저장(§12.1): created_at = updated_at = 현재. */
        fun create(userId: Long, input: SellerProfileInput, now: Instant) = SellerProfile(
            userId = userId,
            shopName = input.shopName,
            intro = input.intro,
            vacationFrom = input.vacationFrom,
            vacationTo = input.vacationTo,
            createdAt = now,
            updatedAt = now,
        )
    }
}

/** 응답 시점의 프로필(§12.1): onVacation은 요청 시각으로 계산한 값. */
class SellerProfileRead(val profile: SellerProfile, now: Instant) {
    val onVacation: Boolean = profile.onVacation(now)
}

interface SellerProfileRepository {
    fun find(userId: Long): SellerProfile?

    fun add(profile: SellerProfile)
}
