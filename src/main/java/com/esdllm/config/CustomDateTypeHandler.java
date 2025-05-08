package com.esdllm.config;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Component
public class CustomDateTypeHandler extends BaseTypeHandler<List<Long>> {
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<Long> parameter, JdbcType jdbcType) throws SQLException {
        ps.setObject(i, parameter);
    }

    @Override
    public List<Long> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        Object dataColumnName = rs.getObject(columnName);
        if (dataColumnName == null) {
            return new ArrayList<>(); // 返回空列表作为默认值
        }
        if (dataColumnName instanceof String) {
            // 去除方括号并分割字符串
            String[] stringArray = ((String) dataColumnName).replaceAll("[\\[\\]]", "").split(",\\s*");
            List<Long> result = new ArrayList<>();
            for (String item : stringArray) {
                try {
                    result.add(Long.parseLong(item));
                } catch (NumberFormatException e) {
                    throw new SQLException("列表中出现无法转换为 Long 的值: " + item);
                }
            }
            return result;
        }
        throw new SQLException("意外类型：" + dataColumnName.getClass().getName());
    }


    @Override
    public List<Long> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return List.of(0L);
    }

    @Override
    public List<Long> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return List.of(0L);
    }
}
