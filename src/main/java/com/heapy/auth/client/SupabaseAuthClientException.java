package com.heapy.auth.client;

public class SupabaseAuthClientException extends RuntimeException {

    private final Reason reason;

    public SupabaseAuthClientException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public SupabaseAuthClientException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public enum Reason {
        INVALID_CREDENTIALS,
        EMAIL_NOT_VERIFIED,
        RATE_LIMITED,
        PROVIDER_UNAVAILABLE
    }
}
