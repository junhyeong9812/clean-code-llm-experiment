package com.ccle.layered

import com.ccle.layered.service.StalePaymentRecovery
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.stereotype.Component

/**
 * 기동 훅: 앱이 HTTP 요청을 받기 전에 StalePaymentRecovery.convertAll()을 부른다(ADR-0003 §9의 7).
 * SmartInitializingSingleton은 모든 싱글턴 빈을 만든 직후(refresh의 finishBeanFactoryInitialization) 불린다.
 * 내장 Tomcat은 그 뒤 finishRefresh의 WebServerStartStopLifecycle.start()에서야 커넥터를 붙여 포트를 열므로,
 * 이 훅이 끝나기 전에는 요청이 들어올 수 없다. 여기서 예외가 나면 기동이 실패한다(PAYING을 남긴 채 요청을 받지 않는다).
 */
@Component
class StartupRecovery(private val recovery: StalePaymentRecovery) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        recovery.convertAll()
    }
}
