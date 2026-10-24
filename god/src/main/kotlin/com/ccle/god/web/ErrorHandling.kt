package com.ccle.god.web

import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.TypeMismatchException
import org.springframework.boot.web.servlet.error.ErrorController
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

// 오류 응답 변환(ADR-0003 §2). 모든 오류는 {"code","message"}이고, 어떤 요청도 5xx로 끝나지 않는다.
// 어떤 오류를 낼지는 Marketplace가 정한다. 여기서는 예외를 응답 모양으로 바꾸기만 한다.

/** 계약에 정해진 오류(상태 + code). Marketplace·컨트롤러가 던진다. */
class ApiException(val status: HttpStatus, val code: String, message: String) : RuntimeException(message)

data class ErrorBody(val code: String, val message: String)

private val log = LoggerFactory.getLogger("com.ccle.god.web.Errors")

/** 계약 밖 상황(버그·예상 못 한 예외)의 응답(ADR-0003 §12의 1). 5xx 대신 이 code와 고정 문구로 돌려주고, 원인은 로그에만 남긴다. */
private fun unexpected(what: String, cause: Throwable?): ResponseEntity<ErrorBody> {
    log.error("예상 못 한 오류: {}", what, cause)
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorBody("UNEXPECTED_ERROR", "예상하지 못한 서버 오류"))
}

/** Spring MVC가 낸 HTTP 상태를 계약의 code로 바꾼다. where = 로그용 요청 메서드·경로. */
private fun fromStatus(status: Int, message: String, cause: Throwable?, where: String): ResponseEntity<ErrorBody> = when {
    // 라우트 없음은 도메인의 NOT_FOUND(없는 입찰·체결·주문·사이즈)와 구분한다(ADR-0003 §2)
    status == 404 -> ResponseEntity.status(404).body(ErrorBody("NO_ROUTE", message))
    status == 405 -> ResponseEntity.status(405).body(ErrorBody("METHOD_NOT_ALLOWED", message))
    // JSON 파싱 실패·타입 불일치·필수 파라미터 누락·지원하지 않는 Content-Type 등: 요청 형식 오류
    status in 400..499 -> ResponseEntity.status(400).body(ErrorBody("INVALID_REQUEST", message))
    else -> unexpected("$where: HTTP $status: $message", cause)
}

@RestControllerAdvice
class ApiErrorAdvice {

    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException): ResponseEntity<ErrorBody> =
        ResponseEntity.status(e.status).body(ErrorBody(e.code, e.message ?: e.code))

    @ExceptionHandler(Exception::class)
    fun other(e: Exception, request: HttpServletRequest): ResponseEntity<ErrorBody> {
        // 라우트 없음(NoResourceFoundException)·메서드 불일치·필수 파라미터 누락 등 Spring MVC 예외는 ErrorResponse를 구현한다
        if (e is ErrorResponse) {
            return fromStatus(e.statusCode.value(), e.body.detail ?: e.message ?: "요청 오류", e, "${request.method} ${request.requestURI}")
        }
        // 그 밖의 바인딩·변환 예외(예: 타입 변환 실패)도 요청 형식 오류로 본다
        if (e is TypeMismatchException || e is HttpMessageNotReadableException) {
            return fromStatus(400, e.message ?: "요청 형식 오류", e, "${request.method} ${request.requestURI}")
        }
        return unexpected("${request.method} ${request.requestURI}: ${e.javaClass.simpleName}: ${e.message}", e)
    }
}

/**
 * MVC 밖(서블릿 필터·컨테이너)에서 난 오류가 /error로 넘어올 때도 같은 모양으로 답한다.
 * Spring Boot 기본 BasicErrorController(code 없는 본문)를 대신한다.
 */
@RestController
class ApiErrorController : ErrorController {

    @RequestMapping("/error")
    fun error(request: HttpServletRequest): ResponseEntity<ErrorBody> {
        val status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) as? Int
        val cause = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) as? Throwable
        val message = (request.getAttribute(RequestDispatcher.ERROR_MESSAGE) as? String)?.takeIf { it.isNotBlank() }
            ?: cause?.message ?: "오류"
        val uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) as? String ?: request.requestURI
        // 직접 /error를 부른 경우(상태 없음)는 없는 라우트와 같다
        return fromStatus(status ?: 404, message, cause, "${request.method} $uri")
    }
}
