package com.prgms.backend.domain.user.controller

import com.prgms.backend.domain.user.dto.request.NicknameRequest
import com.prgms.backend.domain.user.dto.request.SignUpRequest
import com.prgms.backend.domain.user.dto.request.UpdatePasswordRequest
import com.prgms.backend.domain.user.dto.response.EmailCheckResponse
import com.prgms.backend.domain.user.dto.response.NicknameCheckResponse
import com.prgms.backend.domain.user.dto.response.ProfileResponse
import com.prgms.backend.domain.user.dto.response.SignUpResponse
import com.prgms.backend.domain.user.entity.SecurityUser
import com.prgms.backend.domain.user.service.AuthService
import com.prgms.backend.domain.user.service.UserService
import com.prgms.backend.domain.user.service.UserWithdrawService
import com.prgms.backend.global.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/user")
class UserController(
    private val authService: AuthService,
    private val userService: UserService,
    private val userWithdrawService: UserWithdrawService,
) {
    @Operation(
        summary = "이메일 중복 확인",
        description = "사용자가 입력한 이메일이 이용중인지 중복을 확인합니다. ",
    )
    @GetMapping("/check-email")
    @SecurityRequirements
    fun checkEmail(
        @RequestParam @Email @NotNull email: String,
    ): ResponseEntity<ApiResponse<EmailCheckResponse>> {
        val exists = authService.checkEmail(email)

        val response = EmailCheckResponse(!exists)

        return ResponseEntity
            .status(HttpStatus.OK)
            .body(ApiResponse.success(200, response))
    }

    @Operation(
        summary = "닉네임 중복 확인",
        description = "사용자가 입력한 닉네임이 이용중인지 중복을 확인합니다. ",
    )
    @SecurityRequirements
    @GetMapping("/check-nickname")
    fun checkNickname(
        @RequestParam @NotNull nickname: String,
    ): ResponseEntity<ApiResponse<NicknameCheckResponse>> {
        val exists = authService.checkNickname(nickname)

        val response = NicknameCheckResponse(!exists)

        return ResponseEntity
            .status(HttpStatus.OK)
            .body(ApiResponse.success(200, response))
    }

    @Operation(
        summary = "회원가입",
        description = "이메일, 닉네임, 비밀번호를 입력받아 회원가입을 진행합니다.",
    )
    @SecurityRequirements
    @PostMapping("/sign-up")
    fun signUp(
        @Valid @RequestBody signUpRequest: SignUpRequest,
    ): ResponseEntity<ApiResponse<SignUpResponse>> {
        val userId =
            authService.signup(
                email = checkNotNull(signUpRequest.email) { "검증을 통과한 회원가입 요청에는 이메일이 있어야 합니다." },
                nickname = checkNotNull(signUpRequest.nickname) { "검증을 통과한 회원가입 요청에는 닉네임이 있어야 합니다." },
                password = checkNotNull(signUpRequest.password) { "검증을 통과한 회원가입 요청에는 비밀번호가 있어야 합니다." },
                confirmPassword = signUpRequest.confirmPassword,
            )
        val response = SignUpResponse(userId)

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.success(201, response))
    }

    @Operation(
        summary = "내 프로필 조회",
        description = "로그인한 사용자의 이메일과 닉네임을 조회합니다.",
    )
    @GetMapping("/me")
    fun getProfile(
        @AuthenticationPrincipal securityUser: SecurityUser,
    ): ResponseEntity<ApiResponse<ProfileResponse>> {
        val userId = securityUser.id

        val response = userService.getProfile(userId)

        return ResponseEntity
            .status(HttpStatus.OK)
            .body(ApiResponse.success(200, response))
    }

    @Operation(
        summary = "닉네임 변경",
        description = "로그인한 사용자의 닉네임을 새 닉네임으로 변경합니다.",
    )
    @PatchMapping("/me/nickname")
    fun updateNickname(
        @AuthenticationPrincipal securityUser: SecurityUser,
        @RequestBody @Valid request: NicknameRequest,
    ): ResponseEntity<ApiResponse<ProfileResponse>> {
        val userId = securityUser.id
        val nickname = checkNotNull(request.nickname) { "검증을 통과한 닉네임 변경 요청에는 닉네임이 있어야 합니다." }
        val response = userService.updateNickname(userId, nickname)

        return ResponseEntity
            .status(HttpStatus.OK)
            .body(ApiResponse.success(200, response))
    }

    @Operation(
        summary = "비밀번호 변경",
        description = "현재 비밀번호를 확인한 뒤, 새 비밀번호(회원가입과 동일한 형식 규칙 적용)로 변경합니다.",
    )
    @PatchMapping("/me/password")
    fun updatePassword(
        @Valid @RequestBody request: UpdatePasswordRequest,
        @AuthenticationPrincipal securityUser: SecurityUser,
    ): ResponseEntity<ApiResponse<Void>> {
        val userId = securityUser.id
        val password = checkNotNull(request.password) { "검증을 통과한 비밀번호 변경 요청에는 현재 비밀번호가 있어야 합니다." }
        val newPassword = checkNotNull(request.newPassword) { "검증을 통과한 비밀번호 변경 요청에는 새 비밀번호가 있어야 합니다." }
        userService.updatePassword(userId, password, newPassword)

        return ResponseEntity
            .status(HttpStatus.OK)
            .body(ApiResponse.success(200, "비밀번호를 변경했습니다.", null))
    }

    @Operation(
        summary = "회원 탈퇴",
        description = " 로그인한 회원의 탈퇴 가능 여부를 확인하고, 탈퇴를 진행합니다.",
    )
    @DeleteMapping
    fun deleteUser(
        @AuthenticationPrincipal securityUser: SecurityUser,
    ): ResponseEntity<ApiResponse<Void>> {
        val userId = securityUser.id
        userWithdrawService.withdraw(userId)

        val cookie =
            ResponseCookie
                .from("refreshToken", "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/api/auth") // 로그인 때 설정한 path와 반드시 동일해야 지워짐
                .maxAge(0) // 즉시 만료
                .build()

        return ResponseEntity
            .status(HttpStatus.OK)
            .header(HttpHeaders.SET_COOKIE, cookie.toString())
            .body(ApiResponse.success(200, "회원 탈퇴가 완료되었습니다.", null))
    }
}
