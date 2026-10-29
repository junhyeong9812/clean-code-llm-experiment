package com.ccle.god.web

import com.ccle.god.Marketplace
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/** 판매자 프로필 저장·조회(ADR-0007 §12.1). */
@RestController
class SellerProfileController(private val marketplace: Marketplace) {

    @PutMapping("/me/seller-profile")
    fun put(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @RequestBody(required = false) body: String?,
    ): SellerProfileView {
        val userId = marketplace.requireUser(userHeader)
        val json = parseObject(body)
        return marketplace.putSellerProfile(
            userId,
            json.textOrNull("shopName"),
            json.textOrNull("intro"),
            json.dateOrNull("vacationFrom"),
            json.dateOrNull("vacationTo"),
        )
    }

    /** 헤더 불필요 — X-User-Id를 읽지 않는다(ADR-0007 §19의 7). */
    @GetMapping("/sellers/{userId}/profile")
    fun profile(@PathVariable userId: String): SellerProfileView = marketplace.sellerProfile(pathId(userId))
}
