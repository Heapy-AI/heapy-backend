package com.heapy.auth.service;

public enum NextStep {
    TERMS("terms"),
    PROFILE("profile"),
    HOME("home");

    private final String value;

    NextStep(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
