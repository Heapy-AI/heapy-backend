package com.heapy.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Locale;

@Schema(name = "LoginRequest", description = "이메일·비밀번호 로그인 요청")
public record LoginRequest(
        @NotBlank(message = "이메일을 입력해 주세요.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 320, message = "이메일은 320자 이하여야 합니다.")
        @Schema(description = "앞뒤 공백 제거 후 소문자로 정규화되는 이메일", example = "heapy@example.com")
        String email,

        @NotBlank(message = "비밀번호를 입력해 주세요.")
        @Size(min = 8, max = 256, message = "비밀번호는 8자 이상 256자 이하여야 합니다.")
        @Schema(description = "8~256자의 비밀번호", example = "SecurePassword123!", accessMode = Schema.AccessMode.WRITE_ONLY)
        String password
) {

    public LoginRequest {
        email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=[REDACTED]]";
    }
}
