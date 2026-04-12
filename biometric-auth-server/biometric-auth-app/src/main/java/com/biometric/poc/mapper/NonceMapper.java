package com.biometric.poc.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

@Mapper
public interface NonceMapper {

    void insert(
            @Param("nonce") String nonce,
            @Param("deviceId") String deviceId,
            @Param("usedAt") Instant usedAt,
            @Param("expireAt") Instant expireAt);

    int countByNonce(String nonce);

    int deleteExpired(@Param("now") Instant now);

    /** 기기 등록 삭제 시 해당 기기의 nonce 전체 삭제. */
    void deleteByDeviceId(@Param("deviceId") String deviceId);
}
