package com.heapy.common.response;

public record ApiResponse<T>(
        boolean success,
        T data,
        String message,
        Object meta
) {

    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(true, data, message, null);
    }
}
