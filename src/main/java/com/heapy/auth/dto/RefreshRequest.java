package com.heapy.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 갱신 토큰은 요청 본문으로만 받고 로그에 노출하지 않는다. @author 김진우 */
public record RefreshRequest(@NotBlank @Size(max = 8192) String refreshToken) {
    @Override public String toString() { return "RefreshRequest[refreshToken=[REDACTED]]"; }
}
