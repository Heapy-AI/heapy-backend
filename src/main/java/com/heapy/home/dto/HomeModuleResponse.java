package com.heapy.home.dto;

public record HomeModuleResponse(
        String moduleCode,
        boolean visible,
        int displayOrder,
        String state,
        Object content,
        String emptyStateAction
) {
}
