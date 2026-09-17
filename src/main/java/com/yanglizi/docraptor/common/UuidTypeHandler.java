package com.yanglizi.docraptor.common;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * {@link java.util.UUID} ↔ PostgreSQL {@code uuid} 的 MyBatis 类型处理器。
 *
 * <p><b>为什么必须自己写（实测，不是想当然）</b>：
 * MyBatis 3.5.19 的 {@code org.apache.ibatis.type} 包一共 45 个 TypeHandler，
 * <b>没有 {@code UUIDTypeHandler}</b>（只有 {@code UnknownTypeHandler}）。
 * 而 MyBatis 在按类型找 handler 时，会沿父类向上查找，
 * {@code TypeHandlerRegistry.getJdbcHandlerMapForSuperclass()} 一旦发现父类是 {@code Object} 就<b>直接返回 null</b>；
 * {@code UUID} 的父类恰好就是 {@code Object}，于是 {@code #{id}}（javaType = java.util.UUID）
 * 解析不到任何 handler，导致：
 * <pre>
 * java.lang.IllegalStateException: Type handler was null on parameter mapping for property 'id'.
 *   It was either not specified and/or could not be found for the javaType (java.util.UUID) : jdbcType (null) combination.
 * </pre>
 * 该异常发生在 <b>SqlSessionFactory 构建阶段</b>（解析 mapper XML 时），
 * 会让整个 Spring 上下文启动失败 —— 用 {@code java -cp target/classes ...} 直接启动可复现
 * （Lead 于 22:18 实测：Tomcat 已初始化到 8081，随后 refresh 失败退出）。
 *
 * <p>注册位置：{@code src/main/resources/mybatis/mybatis-config.xml} 的 {@code <typeHandlers>}。
 */
@MappedTypes(UUID.class)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, UUID parameter, JdbcType jdbcType)
            throws SQLException {
        // PG 驱动支持直接 setObject(UUID)，会映射为 uuid 类型
        ps.setObject(i, parameter);
    }

    @Override
    public UUID getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toUuid(rs.getObject(columnName));
    }

    @Override
    public UUID getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toUuid(rs.getObject(columnIndex));
    }

    @Override
    public UUID getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toUuid(cs.getObject(columnIndex));
    }

    private static UUID toUuid(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        // 兜底：某些驱动/场景下 uuid 会以字符串返回
        return UUID.fromString(value.toString());
    }
}
