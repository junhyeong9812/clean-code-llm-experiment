package com.ccle.layered.service

import com.ccle.layered.domain.Failure
import com.ccle.layered.domain.MarketException
import com.ccle.layered.domain.UserRepository
import org.springframework.stereotype.Service

/** 요청한 사용자 확인(ADR-0003 §2). 헤더가 필요한 엔드포인트는 본문을 파싱하기 전에 부른다(판정 순서 401 → 400). */
@Service
class UserService(private val users: UserRepository) {

    /** userId = `X-User-Id` 헤더 값(없거나 숫자가 아니면 null). null·없는 사용자 → 401 UNKNOWN_USER. */
    fun requireUser(userId: Long?): Long {
        if (userId == null) throw MarketException(Failure.UNKNOWN_USER, "X-User-Id 헤더가 없거나 숫자가 아니다")
        users.find(userId) ?: throw MarketException(Failure.UNKNOWN_USER, "없는 사용자: $userId")
        return userId
    }
}
