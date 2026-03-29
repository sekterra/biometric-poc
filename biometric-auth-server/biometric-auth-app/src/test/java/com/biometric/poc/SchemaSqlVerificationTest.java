package com.biometric.poc;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * schema.sql 적용 후 H2 메타데이터 검증 (H2 콘솔에서 실행하는 SQL과 동등).
 */
@SpringBootTest
class SchemaSqlVerificationTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void columns_types_and_public_key_length() {
        // H2 2.x: TYPE_NAME 컬럼 없음 → DATA_TYPE 사용 (콘솔 확인 시 동일하게 DATA_TYPE 권장)
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        """
                        SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH
                        FROM INFORMATION_SCHEMA.COLUMNS
                        WHERE TABLE_NAME IN ('BIOMETRIC_DEVICE','BIOMETRIC_SESSION','BIOMETRIC_NONCE')
                        ORDER BY TABLE_NAME, ORDINAL_POSITION
                        """);

        assertFalse(rows.isEmpty(), "expected INFORMATION_SCHEMA.COLUMNS rows");

        Map<String, Object> publicKeyRow =
                rows.stream()
                        .filter(r -> "PUBLIC_KEY_B64".equals(r.get("COLUMN_NAME")))
                        .findFirst()
                        .orElseThrow();

        assertEquals("BIOMETRIC_DEVICE", publicKeyRow.get("TABLE_NAME"));
        assertEquals(
                512,
                ((Number) publicKeyRow.get("CHARACTER_MAXIMUM_LENGTH")).intValue(),
                "PUBLIC_KEY_B64 must be VARCHAR(512)");
    }

    @Test
    void table_and_column_remarks_exist() {
        List<Map<String, Object>> tableRemarks =
                jdbcTemplate.queryForList(
                        """
                        SELECT TABLE_NAME, REMARKS
                        FROM INFORMATION_SCHEMA.TABLES
                        WHERE TABLE_NAME IN ('BIOMETRIC_DEVICE','BIOMETRIC_SESSION','BIOMETRIC_NONCE')
                        """);

        assertEquals(3, tableRemarks.size());
        for (Map<String, Object> row : tableRemarks) {
            assertNotNull(row.get("REMARKS"), "TABLE REMARKS for " + row.get("TABLE_NAME"));
        }

        List<Map<String, Object>> colRemarks =
                jdbcTemplate.queryForList(
                        """
                        SELECT TABLE_NAME, COLUMN_NAME, REMARKS
                        FROM INFORMATION_SCHEMA.COLUMNS
                        WHERE TABLE_NAME IN ('BIOMETRIC_DEVICE','BIOMETRIC_SESSION','BIOMETRIC_NONCE')
                        ORDER BY TABLE_NAME, ORDINAL_POSITION
                        """);

        assertFalse(colRemarks.isEmpty());
        long withRemark =
                colRemarks.stream().filter(r -> r.get("REMARKS") != null).count();
        assertEquals(colRemarks.size(), withRemark, "every column should have REMARKS");
    }
}
