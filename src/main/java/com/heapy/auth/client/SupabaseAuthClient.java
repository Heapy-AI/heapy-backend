package com.heapy.auth.client;

public interface SupabaseAuthClient {

    SupabaseAuthSession login(String email, String password);
    SupabaseAuthSession refresh(String refreshToken);
}
