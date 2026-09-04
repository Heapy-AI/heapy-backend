package com.heapy.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record LoginRequest(
        @NotBlank(message = "이메일을 입력해 주세요.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 320, message = "이메일은 320자 이하여야 합니다.")
        String email,

        @NotBlank(message = "비밀번호를 입력해 주세요.")
        @Size(min = 8, max = 256, message = "비밀번호는 8자 이상 256자 이하여야 합니다.")
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
