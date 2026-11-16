package com.bms.dto.response;

/**
 * T6: changing a password revokes every session for that user, including the
 * one that asked, so the response carries a replacement access token.
 */
public class ChangePasswordResponse {
    private String accessToken;

    public ChangePasswordResponse() {
    }

    public ChangePasswordResponse(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
}
