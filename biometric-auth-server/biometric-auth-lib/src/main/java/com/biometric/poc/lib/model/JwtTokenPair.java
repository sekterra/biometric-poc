package com.biometric.poc.lib.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JwtTokenPair {

    private String accessToken;
    private String refreshToken;
    private int expiresIn;
}
