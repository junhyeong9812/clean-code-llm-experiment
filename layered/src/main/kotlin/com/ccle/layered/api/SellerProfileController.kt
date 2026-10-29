package com.ccle.layered.api

import com.ccle.layered.domain.SellerProfileRead
import com.ccle.layered.service.SellerProfileService
import com.ccle.layered.service.UserService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/** 판매자 프로필(ADR-0007 §12.1). onVacation = 오늘(KST)이 [vacationFrom, vacationTo] 안(계산 값). */
data class SellerProfileView(
    val userId: Long,
    val shopName: String,
    val intro: String,
    val vacationFrom: LocalDate?,
    val vacationTo: LocalDate?,
    val onVacation: Boolean,
) {
    constructor(r: SellerProfileRead) : this(
        r.profile.userId, r.profile.shopName, r.profile.intro, r.profile.vacationFrom, r.profile.vacationTo, r.onVacation,
    )
}

/** 판매자 프로필 저장·조회(ADR-0007 §12.1). */
@RestController
class SellerProfileController(private val users: UserService, private val profiles: SellerProfileService) {

    @PutMapping("/me/seller-profile")
    fun put(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @RequestBody(required = false) body: String?,
    ): SellerProfileView {
        val userId = users.requireUser(userIdOf(userHeader))
        val json = parseObject(body)
        return SellerProfileView(
            profiles.put(
                userId,
                json.textOrNull("shopName"),
                json.textOrNull("intro"),
                json.dateOrNull("vacationFrom"),
                json.dateOrNull("vacationTo"),
            ),
        )
    }

    /** 헤더 불필요 — X-User-Id를 읽지 않는다(ADR-0007 §19의 7). */
    @GetMapping("/sellers/{userId}/profile")
    fun profile(@PathVariable userId: String): SellerProfileView = SellerProfileView(profiles.profile(pathId(userId)))
}
