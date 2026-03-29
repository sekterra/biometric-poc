package com.biometric.poc.mapper.handler;

import com.biometric.poc.lib.model.DeviceStatus;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@MappedTypes(DeviceStatus.class)
public class DeviceStatusTypeHandler extends BaseTypeHandler<DeviceStatus> {

    @Override
    public void setNonNullParameter(
            PreparedStatement ps, int i, DeviceStatus parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, parameter.name());
    }

    @Override
    public DeviceStatus getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String v = rs.getString(columnName);
        return v == null ? null : DeviceStatus.valueOf(v);
    }

    @Override
    public DeviceStatus getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String v = rs.getString(columnIndex);
        return v == null ? null : DeviceStatus.valueOf(v);
    }

    @Override
    public DeviceStatus getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String v = cs.getString(columnIndex);
        return v == null ? null : DeviceStatus.valueOf(v);
    }
}
