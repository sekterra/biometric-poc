package com.biometric.poc.lib.challenge;

import com.biometric.poc.lib.model.SessionData;
import com.biometric.poc.lib.store.SessionStore;
import com.biometric.poc.lib.store.SessionStoreImpl;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ChallengeServiceTest {

    @Test
    void generateChallenge_returns64charHex() {
        SessionStore store = mock(SessionStore.class);
        ChallengeService service = new ChallengeService(store);

        String challenge = service.generateChallenge();

        assertEquals(64, challenge.length());
        assertTrue(challenge.matches("[0-9a-f]{64}"), "expected lowercase hex: " + challenge);
    }

    @Test
    void createSession_thenValidateSession_success() {
        SessionStoreImpl store = new SessionStoreImpl();
        ChallengeService service = new ChallengeService(store);

        SessionData created =
                service.createSession("device-1", "USER01", "nonce123", System.currentTimeMillis());
        SessionData validated = service.validateSession(created.getSessionId());

        assertEquals(created.getSessionId(), validated.getSessionId());
        assertEquals(created.getServerChallengeHex(), validated.getServerChallengeHex());
        assertEquals(created.getDeviceId(), validated.getDeviceId());
        assertEquals(created.getUserId(), validated.getUserId());
        assertEquals(created.getClientNonce(), validated.getClientNonce());
        assertEquals(created.getTimestamp(), validated.getTimestamp());
    }

    @Test
    void validateSession_expired_throwsException() {
        SessionStoreImpl store = new SessionStoreImpl();
        ChallengeService service = new ChallengeService(store);

        Instant created = Instant.now().minusSeconds(120);
        SessionData expired =
                SessionData.builder()
                        .sessionId("expired-session")
                        .deviceId("d")
                        .userId("u")
                        .serverChallengeHex("ab".repeat(32))
                        .clientNonce("n")
                        .timestamp(1L)
                        .expireAt(Instant.now().minusSeconds(60))
                        .used(false)
                        .createdAt(created)
                        .build();
        store.save(expired);

        assertThrows(
                IllegalStateException.class, () -> service.validateSession("expired-session"));
    }
}
