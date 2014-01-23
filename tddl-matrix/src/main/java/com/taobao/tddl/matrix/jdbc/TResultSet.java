package com.taobao.tddl.matrix.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.taobao.tddl.common.exception.TddlException;
import com.taobao.tddl.common.exception.TddlRuntimeException;
import com.taobao.tddl.common.utils.GeneralUtil;
import com.taobao.tddl.common.utils.TStringUtil;
import com.taobao.tddl.executor.cursor.ICursorMeta;
import com.taobao.tddl.executor.cursor.ResultCursor;
import com.taobao.tddl.executor.rowset.IRowSet;
import com.taobao.tddl.executor.utils.ExecUtils;
import com.taobao.tddl.optimizer.config.table.ColumnMeta;
import com.taobao.tddl.optimizer.core.datatype.DataType;

/**
 * @author mengshi.sunmengshi 2013-11-22 下午3:26:23
 * @since 5.1.0
 */
public class TResultSet implements ResultSet {

    /** Has this result set been closed? */
    protected boolean             isClosed                  = false;

    private final ResultCursor    resultCursor;
    private IRowSet               currentKVPair;
    private IRowSet               cacheRowSetToBuildMeta    = null;
    private TResultSetMetaData    resultSetMetaData         = null;
    private boolean               wasNull;
    private boolean               isLoigcalIndexEqualActualIndex;
    private Map<Integer, Integer> logicalIndexToActualIndex = null;

    public TResultSet(ResultCursor resultCursor){
        this.resultCursor = resultCursor;
        if (this.resultCursor != null && this.resultCursor.getOriginalSelectColumns() != null
            && !this.resultCursor.getOriginalSelectColumns().isEmpty()) {
            this.resultSetMetaData = new TResultSetMetaData(ExecUtils.convertIColumnsToColumnMeta(this.resultCursor.getOriginalSelectColumns()));
        }

    }

    // 游标指向下一跳记录
    @Override
    public boolean next() throws SQLException {
        checkClosed();
        IRowSet kvPair;
        try {
            if (cacheRowSetToBuildMeta != null) {
                kvPair = cacheRowSetToBuildMeta;
                cacheRowSetToBuildMeta = null;
            } else {
                kvPair = resultCursor.next();
            }

            this.currentKVPair = kvPair;
        } catch (Exception e) {
            this.currentKVPair = null;
            throw new SQLException(e);
        }
        if (null != kvPair) {
            return true;
        } else {
            return false;
        }
    }

    public int getAffectRows() throws SQLException {
        if (next()) {
            Integer index = getIndexByColumnLabel(ResultCursor.AFFECT_ROW);
            return currentKVPair.getInteger(index);
        } else {
            return 0;
        }
    }

    private void checkClosed() throws SQLException {
        if (this.isClosed) {
            throw new SQLException("ResultSet.Operation_not_allowed_after_ResultSet_closed");
        }
    }

    @Override
    public void close() throws SQLException {
        if (isClosed) {
            return;
        }
        try {
            this.resultSetMetaData = null;
            List<TddlException> exs = new ArrayList();
            exs = this.resultCursor.close(exs);
            if (!exs.isEmpty()) {
                throw GeneralUtil.mergeException(exs);
            }
            isClosed = true;
        } catch (Exception e) {
            throw new SQLException(e);

        }
    }

    @Override
    public boolean isClosed() throws SQLException {
        return this.isClosed;
    }

    private void validateColumnLabel(String columnLabel) throws SQLException {
        if (!this.getMetaData().columnIsExist(columnLabel)) {
            throw new SQLException("column " + columnLabel + " doesn't exist!, " + this.getMetaData().getColumnMetas());
        }
    }

    private DataType getColumnLabelDataType(String columnLabel) throws SQLException {
        return this.getMetaData().getColumnDataType(columnLabel);
    }

    private void validateColumnIndex(int columnIndex) throws SQLException {
        if (columnIndex < 0 || columnIndex > this.getMetaData().getColumnCount()) {
            throw new SQLException("columnIndex 越界，column size：" + this.getMetaData().getColumnCount());
        }
    }

    /**
     * @param logicalIndex 用户select时的index
     * @return IRowSet中实际的index
     * @throws SQLException
     */
    private int getActualIndex(int logicalIndex) {
        if (this.logicalIndexToActualIndex == null) {
            logicalIndexToActualIndex = new HashMap<Integer, Integer>();
            isLoigcalIndexEqualActualIndex = true;
            if (this.currentKVPair == null) {
                return logicalIndex;
            }

            ICursorMeta cm = currentKVPair.getParentCursorMeta();
            if (cm.isSureLogicalIndexEqualActualIndex()) {
                // 如果确定相等，就不需要挨个去判断了
                isLoigcalIndexEqualActualIndex = true;
            } else {
                try {
                    for (int i = 0; i < this.getMetaData().getColumnCount(); i++) {
                        ColumnMeta ic = this.getMetaData().getColumnMetas().get(i);
                        String name = ic.getName();
                        String tableName = ic.getTableName();
                        Integer indexInCursorMeta = cm.getIndex(tableName, name);

                        if (indexInCursorMeta == null && ic.getAlias() != null) {
                            indexInCursorMeta = cm.getIndex(tableName, ic.getAlias());
                        }

                        if (indexInCursorMeta == null) {
                            throw new TddlRuntimeException("不可能出现");
                        }
                        logicalIndexToActualIndex.put(i, indexInCursorMeta);
                        if (i != indexInCursorMeta) {
                            isLoigcalIndexEqualActualIndex = false;
                        }
                    }
                } catch (SQLException e) {
                    throw new TddlRuntimeException(e);
                }
            }
        }

        if (isLoigcalIndexEqualActualIndex) {
            return logicalIndex;
        } else {
            Integer actualIndex = logicalIndexToActualIndex.get(logicalIndex);
            return actualIndex;
        }
    }

    /**
     * 获取index，columnLabel可能是table.columnName的结构
     */
    protected Integer getIndexByColumnLabel(String columnLabel) throws SQLException {
        columnLabel = TStringUtil.upperCase(columnLabel);
        String table = null;
        // String name = null;
        boolean contains = TStringUtil.contains(columnLabel, ".")
                           & !(TStringUtil.contains(columnLabel, "(") & TStringUtil.contains(columnLabel, ")"));
        if (contains) {
            String[] ss = TStringUtil.split(columnLabel, ".");
            if (ss.length != 2) {
                throw new SQLException("lab can only has one dot ");
            }
            table = ss[0];
            // name = ss[1];
        } else {
            // name = columnLabel;
        }
        if (currentKVPair == null) {
            throw new IllegalStateException("确定调用了rs.next并返回true了么？");
        }

        Integer index = currentKVPair.getParentCursorMeta().getIndex(table, columnLabel);
        // if (index == null) {
        // throw new SQLException("can't find index by table " + table +
        // " . column " + name);
        // }
        return index;
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        Integer index = getIndexByColumnLabel(columnLabel);
        String str = currentKVPair.getString(index);
        if (str == null) {
            wasNull = true;
            return str;
        } else {
            wasNull = false;
            return str;
        }
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        validateColumnIndex(columnIndex);
        columnIndex--;
        String str = currentKVPair.getString(getActualIndex(columnIndex));
        if (str == null) {
            wasNull = true;
            return str;
        } else {
            wasNull = false;
            return str;
        }
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        validateColumnLabel(columnLabel);
        Integer index = getIndexByColumnLabel(columnLabel);
        Boolean bool = currentKVPair.getBoolean(index);
        if (null == bool) {
            wasNull = true;
            return false;
        } else {
            wasNull = false;
            return bool;
        }
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        validateColumnIndex(columnIndex);
        columnIndex--;
        Boolean bool = currentKVPair.getBoolean(getActualIndex(columnIndex));
        if (null == bool) {
            wasNull = true;
            return false;
        } else {
            wasNull = false;
            return bool;
        }
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        validateColumnLabel(columnLabel);
        Integer index = getIndexByColumnLabel(columnLabel);
        Short st = currentKVPair.getShort(index);
        if (st == null) {
            wasNull = true;
            return 0;
        } else {
            wasNull = false;
            return st;
        }
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        validateColumnIndex(columnIndex);
        columnIndex--;
        Short st = currentKVPair.getShort(getActualIndex(columnIndex));
        if (st == null) {
            wasNull = true;
            return 0;
        } else {
            wasNull = false;
            return st;
        }
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        validateColumnLabel(columnLabel);
        Integer index = getIndexByColumnLabel(columnLabel);
        if (index == null) {
            wasNull = true;
            return 0;
        }

        Integer inte = currentKVPair.getInteger(index);
        if (inte == null) {
            wasNull = true;
            return 0;
        } else {
            wasNull = false;
            return inte;
        }
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        validateColumnIndex(columnIndex);
        columnIndex--;
        Integer inte = currentKVPair.getInteger(getActualIndex(columnIndex));
        if (inte == null) {
            wasNull = true;
            return 0;
        } else {
            wasNull = false;
            return inte;
        }
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        validateColumnLabel(columnLabel);
        Integer index = getIndexByColumnLabel(columnLabel);
        if (index == null) {
            wasNull = true;
            return 0l;
        }

        Long l = currentKVPair.getLong(index);
        if (l == null) {
            wasNull = true;
            return 0l;
        } else {
            wasNull = false;
            return l;
        }
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        validateColumnIndex(columnIndex);
        columnIndex--;
        Long l = currentKVPair.getLong(getActualIndex(columnIndex));
        if (l == null) {
            wasNull = true;
            return 0;
        } else {
            wasNull = false;
            return l;
        }
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        validateColumnLabel(columnLabel);
        Integer index = getIndexByColumnLabel(columnLabel);
        if (index == null) {
            wasNull = true;
            return (float) 0.0;
        }

        Float fl = currentKVPair.getFloat(index);
        if (fl == null) {
            wasNull = true;
            return 0;
        } else {
            wasNull = false;
            return fl;
        }
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        validateColumnIndex(columnIndex);
        columnIndex--;
        Float fl = currentKVPair.getFloat(getActualIndex(columnIndex));
        if (fl == null) {
            wasNull = true;
            return 0;
        } else {
            wasNull = false;
            return fl;
        }
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        validateColumnLabel(columnLabel);
        Integer index = getIndexByColumnLabel(columnLabel);
        if (index == null) {
            wasNull = true;
            return 0.0;
        }

        Double doub = currentKVPair.getDouble(index);
        if (doub == null) {
            wasNull = true;
            return 0;
        } else {
            wasNull = false;
            return doub;
        }
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        validateColumnIndex(columnIndex);
        columnIndex--;
        Double doub = currentKVPair.getDouble(getActualIndex(columnIndex));
        if (doub == null) {
            wasNull = true;
            return 0;
        } else {
            wasNull = false;
            return doub;
        }
    }

    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        validateColumnLabel(columnLabel);
        Integer index = getIndexByColumnLabel(columnLabel);
        if (index == null) {
            wasNull = true;
            return null;
        }

        byte[] bytes = currentKVPair.getBytes(index);
        if (bytes == null) {
            wasNull = true;
            return null;
        } else {
            wasNull = false;
            return bytes;
        }
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        validateColumnIndex(columnIndex);
        columnIndex--;
        byte[] bytes = currentKVPair.getBytes(getActualIndex(columnIndex));
        if (bytes == null) {
            wasNull = true;
            return null;
        } else {
            wasNull = false;
            return bytes;
        }
    }

    // tddl将date按long型对待
    @Override
    public Date getDate(int columnIndex) throws SQLException {
        validateColumnIndex(columnIndex);
        columnIndex--;
        Date date = currentKVPair.getDate(getActualIndex(columnIndex));
        if (date == null) {
            wasNull = true;
            return null;
        } else {
            wasNull = false;
            return date;
        }
    }

    @Override
    public Date getDate(String columnLabel) throws SQLException {
        validateColumnLabel(columnLabel);
        Integer index = getIndexByColumnLabel(columnLabel);
        if (index == null) {
            wasNull = true;
            return null;
        }
        Date date = currentKVPair.getDate(index);
        if (date == null) {
            wasNull = true;
            return null;
        } else {
            wasNull = false;
            return date;
        }
    }

    @Override
    public byte getByte(String columnLabel) throws SQLException {
        validateColumnLabel(columnLabel);
        Integer index = getIndexByColumnLabel(columnLabel);
        if (index == null) {
            return 0;
        }

        byte[] bytes = currentKVPair.getBytes(index);
        if (bytes == null || bytes.length == 0) {
            return 0;
        } else {
            return bytes[0];
        }
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        validateColumnIndex(columnIndex);
        columnIndex--;
        byte[] bytes = currentKVPair.getBytes(getActualIndex(columnIndex));
        if (bytes == null) {
            wasNull = true;
            return 0;
        } else {
            wasNull = false;
            return bytes[0];
        }
    }

    @Override
    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        validateColumnLabel(columnLabel);
        Integer index = getIndexByColumnLabel(columnLabel);
        if (index == null) {
            wasNull = true;
            return null;
        }
        Timestamp ts = currentKVPair.getTimestamp(index);
        if (ts == null) {
            wasNull = true;
            return null;
        } else {
            wasNull = false;
            return ts;
        }
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        validateColumnIndex(columnIndex);
        columnIndex--;
        Timestamp ts = currentKVPair.getTimestamp(getActualIndex(columnIndex));
        if (ts == null) {
            wasNull = true;
            return null;
        } else {
            wasNull = false;
            return ts;
        }
    }

    @Override
    public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
        Timestamp ts = getTimestamp(columnIndex);
        if (ts == null) {
            wasNull = true;
            return null;
        }
        wasNull = false;
        cal.setTimeInMillis(ts.getTime());
        return new Timestamp(cal.getTimeInMillis());
    }

    @Override
    public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
        Timestamp ts = getTimestamp(columnLabel);
        if (ts == null) {
            wasNull = true;
            return null;
        }
        wasNull = false;
        cal.setTimeInMillis(ts.getTime());
        return new Timestamp(cal.getTimeInMillis());
    }

    @Override
    public Time getTime(String columnLabel) throws SQLException {
        Timestamp ts = getTimestamp(columnLabel);
        if (ts == null) {
            wasNull = true;
            return null;
        }
        wasNull = false;
        return new Time(ts.getTime());
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        Timestamp ts = getTimestamp(columnIndex);
        if (ts == null) {
            wasNull = true;
            return null;
        }
        wasNull = false;
        return new Time(ts.getTime());
    }

    @Override
    public Time getTime(int columnIndex, Calendar cal) throws SQLException {
        Timestamp ts = getTimestamp(columnIndex);
        if (ts == null) {
            wasNull = true;
            return null;
        }
        wasNull = false;
        cal.setTimeInMillis(ts.getTime());
        return new Time(cal.getTimeInMillis());
    }

    @Override
    public Time getTime(String columnLabel, Calendar cal) throws SQLException {
        Timestamp ts = getTimestamp(columnLabel);
        if (ts == null) {
            wasNull = true;
            return null;
        }
        wasNull = false;
        cal.setTimeInMillis(ts.getTime());
        return new Time(cal.getTimeInMillis());
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        validateColumnLabel(columnLabel);
        DataType a = getColumnLabelDataType(columnLabel);
        // boolean dataFlag = false;
        // if (a != null) {
        // dataFlag = (a == DataType.DatetimeType);
        // } else {
        // dataFlag = false;
        // }

        try {
            Integer index = getIndexByColumnLabel(columnLabel);
            if (index == null) {
                wasNull = true;
                return null;
            }

            Object result = currentKVPair.getObject(index);
            if (null == result) {
                wasNull = true;
                return null;
            } else {
                wasNull = false;
                return result;
            }
        } catch (Exception e) {
            throw new SQLException(e);
        }
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        validateColumnIndex(columnIndex);
        columnIndex--;
        Object obj = currentKVPair.getObject(getActualIndex(columnIndex));
        if (obj == null) {
            wasNull = true;
            return null;
        } else {
            wasNull = false;
            return obj;
        }
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        validateColumnLabel(columnLabel);
        Object value = getObject(columnLabel);
        if (value == null) {
            wasNull = true;
            return null;
        }

        wasNull = false;
        return this.validBigDecimal(value);
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        return getBigDecimal(columnIndex);
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        validateColumnIndex(columnIndex);
        Object value = getObject(columnIndex);
        if (value == null) {
            wasNull = true;
            return null;
        }

        wasNull = false;
        return this.validBigDecimal(value);
    }

    private BigDecimal validBigDecimal(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Long) {
            return new BigDecimal((Long) value);
        }

        if (value instanceof Short) {
            return new BigDecimal((Short) value);
        }

        if (value instanceof Double) {
            return new BigDecimal((Double) value);
        }

        if (value instanceof Float) {
            return new BigDecimal((Float) value);
        }

        if (value instanceof Integer) {
            return new BigDecimal((Integer) value);
        }

        if (value instanceof BigInteger) {
            return new BigDecimal((BigInteger) value);
        }

        if (value instanceof String) {
            return new BigDecimal((String) value);
        }

        if (value instanceof Date) {
            return new BigDecimal(((Date) value).getTime());
        }

        throw new RuntimeException("不支持类型" + value.getClass().getSimpleName() + " 到BigDecimal的转换");
    }

    @Override
    public boolean wasNull() throws SQLException {
        return wasNull;
    }

    @Override
    public TResultSetMetaData getMetaData() throws SQLException {
        checkClosed();
        if (this.resultSetMetaData != null) {
            return this.resultSetMetaData;
        }
        IRowSet kvPair = null;
        if (this.currentKVPair != null) {
            kvPair = currentKVPair;
        } else {
            if (this.cacheRowSetToBuildMeta == null) {
                try {
                    cacheRowSetToBuildMeta = resultCursor.next();
                } catch (Exception e) {
                    throw new SQLException(e);
                }
            }
            kvPair = cacheRowSetToBuildMeta;
        }
        if (kvPair == null) {
            resultSetMetaData = new TResultSetMetaData(new ArrayList(0));
        } else {
            resultSetMetaData = new TResultSetMetaData(kvPair.getParentCursorMeta().getColumns());
        }
        return resultSetMetaData;
    }

    @Override
    public Blob getBlob(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Clob getClob(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Blob getBlob(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Clob getClob(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        return;
    }

    @Override
    public int getFetchDirection() throws SQLException {
        return ResultSet.FETCH_FORWARD;
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        return;
    }

    @Override
    public int getFetchSize() throws SQLException {
        return 0;
    }

    @Override
    public int getType() throws SQLException {
        return TYPE_FORWARD_ONLY;
    }

    @Override
    public int getConcurrency() throws SQLException {
        return ResultSet.CONCUR_READ_ONLY;
    }

    // ----------------------------- 未实现的类型 ------------------------

    @Override
    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();

    }

    @Override
    public InputStream getUnicodeStream(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public InputStream getBinaryStream(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public InputStream getAsciiStream(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public InputStream getUnicodeStream(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public InputStream getBinaryStream(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getCursorName() throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Reader getCharacterStream(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Reader getCharacterStream(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isFirst() throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isLast() throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void beforeFirst() throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void afterLast() throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean first() throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean last() throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getRow() throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean absolute(int row) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean relative(int rows) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean previous() throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean rowUpdated() throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean rowInserted() throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean rowDeleted() throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateNull(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateBoolean(int columnIndex, boolean x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateByte(int columnIndex, byte x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateShort(int columnIndex, short x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateInt(int columnIndex, int x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateLong(int columnIndex, long x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateFloat(int columnIndex, float x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateDouble(int columnIndex, double x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateString(int columnIndex, String x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateBytes(int columnIndex, byte[] x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateDate(int columnIndex, Date x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateTime(int columnIndex, Time x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateObject(int columnIndex, Object x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateNull(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateBoolean(String columnLabel, boolean x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateByte(String columnLabel, byte x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateShort(String columnLabel, short x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateInt(String columnLabel, int x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateLong(String columnLabel, long x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateFloat(String columnLabel, float x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateDouble(String columnLabel, double x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateString(String columnLabel, String x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateBytes(String columnLabel, byte[] x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateDate(String columnLabel, Date x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateTime(String columnLabel, Time x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, int length) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateObject(String columnLabel, Object x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void insertRow() throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateRow() throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteRow() throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void refreshRow() throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Statement getStatement() throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Ref getRef(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Array getArray(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Ref getRef(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Array getArray(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Date getDate(int columnIndex, Calendar cal) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Date getDate(String columnLabel, Calendar cal) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public URL getURL(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public URL getURL(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateRef(int columnIndex, Ref x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateRef(String columnLabel, Ref x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateBlob(int columnIndex, Blob x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateBlob(String columnLabel, Blob x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateClob(int columnIndex, Clob x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateClob(String columnLabel, Clob x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateArray(int columnIndex, Array x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateArray(String columnLabel, Array x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public RowId getRowId(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public RowId getRowId(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateRowId(int columnIndex, RowId x) throws SQLException {
        throw new UnsupportedOperationException();

    }

    @Override
    public void updateRowId(String columnLabel, RowId x) throws SQLException {
        throw new UnsupportedOperationException();

    }

    @Override
    public int getHoldability() throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateNString(int columnIndex, String nString) throws SQLException {
        throw new UnsupportedOperationException();

    }

    @Override
    public void updateNString(String columnLabel, String nString) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public NClob getNClob(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public NClob getNClob(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public SQLXML getSQLXML(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public SQLXML getSQLXML(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getNString(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getNString(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Reader getNCharacterStream(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Reader getNCharacterStream(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateClob(int columnIndex, Reader reader) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateClob(String columnLabel, Reader reader) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return this.getClass().isAssignableFrom(iface);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        try {
            return (T) this;
        } catch (Exception e) {
            throw new SQLException(e);
        }
    }

}
