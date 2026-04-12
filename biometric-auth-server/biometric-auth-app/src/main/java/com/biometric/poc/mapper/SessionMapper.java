package com.biometric.poc.mapper;

import com.biometric.poc.lib.model.SessionData;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

@Mapper
public interface SessionMapper {

    void insert(SessionData sessionData);

    SessionData selectBySessionId(String sessionId);

    void markUsed(@Param("sessionId") String sessionId);

    /** 만료된 세션 행 삭제. 반환: 삭제된 행 수. */
    int deleteExpired(@Param("now") Instant now);

    /** 기기 등록 삭제 시 해당 기기의 세션 전체 삭제. */
    void deleteByDeviceId(@Param("deviceId") String deviceId);
}
