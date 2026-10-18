package com.ccle.layered.service

import com.ccle.layered.domain.Bid
import com.ccle.layered.domain.BidEventRepository
import com.ccle.layered.domain.Match
import com.ccle.layered.domain.Trade
import com.ccle.layered.domain.TradeRepository
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 두 입찰의 체결을 저장한다(§3.3). 쓰기 순서: 체결 INSERT → 두 입찰 MATCHED → bid_events 구매 입찰 행, 판매 입찰 행.
 * 그 사이즈를 잠근 호출자의 트랜잭션 안에서 부른다.
 */
@Component
class MatchRecorder(private val trades: TradeRepository, private val events: BidEventRepository) {

    fun record(a: Bid, b: Bid, now: Instant): Trade {
        val match = Match(a, b)
        val trade = match.trade(now)
        trades.add(trade)
        match.markMatched(trade, now).forEach(events::add)
        return trade
    }
}
