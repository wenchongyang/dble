package io.mycat.plan.common.item;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import io.mycat.backend.mysql.CharsetUtil;
import io.mycat.config.ErrorCode;
import io.mycat.net.mysql.FieldPacket;
import io.mycat.plan.NamedField;
import io.mycat.plan.PlanNode;
import io.mycat.plan.PlanNode.PlanNodeType;
import io.mycat.plan.common.context.NameResolutionContext;
import io.mycat.plan.common.context.ReferContext;
import io.mycat.plan.common.exception.MySQLOutPutException;
import io.mycat.plan.common.field.Field;
import io.mycat.plan.common.time.MySQLTime;
import io.mycat.plan.node.JoinNode;
import io.mycat.util.StringUtil;
import org.apache.commons.lang.StringUtils;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

public class ItemField extends ItemIdent {

    private Field field;

    /* index如果有值的话,代表这个Item_field真实的下标值,需要在调用val之前调用setField方法 */
    private int index = -1;

    public ItemField(String dbName, String tableName, String fieldName) {
        super(dbName, tableName, fieldName);
    }

    public ItemField(Field field) {
        super(null, field.getTable(), field.getName());
        setField(field);
    }

    /**
     * 保存index
     *
     * @param index
     */
    public ItemField(int index) {
        super(null, "", "");
        this.index = index;
    }

    public void setField(List<Field> fields) {
        assert (fields != null);
        setField(fields.get(index));
    }

    protected void setField(Field field) {
        this.field = field;
        maybeNull = field.maybeNull(); // 有可能为null
        decimals = field.getDecimals();
        tableName = field.getTable();
        itemName = field.getName();
        dbName = field.getDbname();
        maxLength = field.getFieldLength();
        charsetIndex = field.getCharsetIndex();
        fixed = true;
    }

    @Override
    public ItemType type() {
        return ItemType.FIELD_ITEM;
    }

    @Override
    public ItemResult resultType() {
        return field.resultType();
    }

    @Override
    public ItemResult numericContextResultType() {
        return field.numericContextResultType();
    }

    @Override
    public FieldTypes fieldType() {
        return field.fieldType();
    }

    @Override
    public byte[] getRowPacketByte() {
        return field.getPtr();
    }

    public ItemResult cmpType() {
        return field.cmpType();
    }

    @Override
    public int hashCode() {
        int prime = 31;
        int hashCode = dbName == null ? 0 : dbName.hashCode();
        hashCode = hashCode * prime + (tableName == null ? 0 : tableName.hashCode());
        hashCode = hashCode * prime + (itemName == null ? 0 : itemName.hashCode());
        return hashCode;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (!(obj instanceof ItemField))
            return false;
        ItemField other = (ItemField) obj;
        return StringUtils.equals(getTableName(), other.getTableName()) &&
                StringUtils.equalsIgnoreCase(getItemName(), other.getItemName());
    }

    @Override
    public BigDecimal valReal() {
        if (nullValue = field.isNull())
            return BigDecimal.ZERO;
        return field.valReal();
    }

    @Override
    public BigInteger valInt() {
        if (nullValue = field.isNull())
            return BigInteger.ZERO;
        return field.valInt();
    }

    @Override
    public long valTimeTemporal() {
        if ((nullValue = field.isNull()))
            return 0;
        return field.valTimeTemporal();
    }

    @Override
    public long valDateTemporal() {
        if ((nullValue = field.isNull()))
            return 0;
        return field.valDateTemporal();
    }

    @Override
    public BigDecimal valDecimal() {
        if (nullValue = field.isNull())
            return null;
        return field.valDecimal();
    }

    @Override
    public String valStr() {
        if (nullValue = field.isNull())
            return null;
        return field.valStr();
    }

    @Override
    public boolean getDate(MySQLTime ltime, long fuzzydate) {
        if ((nullValue = field.isNull()) || field.getDate(ltime, fuzzydate)) {
            ltime.setZeroTime(ltime.getTimeType());
            return true;
        }
        return false;
    }

    @Override
    public boolean getTime(MySQLTime ltime) {
        if ((nullValue = field.isNull()) || field.getTime(ltime)) {
            ltime.setZeroTime(ltime.getTimeType());
            return true;
        }
        return false;
    }

    @Override
    public boolean isNull() {
        return field.isNull();
    }

    @Override
    public void makeField(FieldPacket fp) {
        field.makeField(fp);
        try {
            if (itemName != null) {
                fp.setName(itemName.getBytes(CharsetUtil.getJavaCharset(charsetIndex)));
            }
            if ((tableName != null)) {
                fp.setTable(tableName.getBytes(CharsetUtil.getJavaCharset(charsetIndex)));
            }
            if (dbName != null) {
                fp.setDb(dbName.getBytes(CharsetUtil.getJavaCharset(charsetIndex)));
            }
        } catch (UnsupportedEncodingException e) {
            LOGGER.warn("parse string exception!", e);
        }
    }

    public String getTableName() {
        return tableName;
    }

    @Override
    public Item fixFields(NameResolutionContext context) {
        if (this.isWild())
            return this;
        String tmpFieldTable = null;
        String tmpFieldName = getItemName();
        PlanNode planNode = context.getPlanNode();
        if (context.getPlanNode().type() == PlanNodeType.MERGE) {
            return getMergeNodeColumn(tmpFieldTable, tmpFieldName, planNode);
        }
        Item column = null;
        if (context.isFindInSelect()) {
            // 尝试从selectlist中查找一次
            if (StringUtils.isEmpty(getTableName())) {
                for (NamedField namedField : planNode.getOuterFields().keySet()) {
                    if (StringUtils.equalsIgnoreCase(tmpFieldName, namedField.getName())) {
                        if (column == null) {
                            column = planNode.getOuterFields().get(namedField);
                        } else
                            throw new MySQLOutPutException(ErrorCode.ER_OPTIMIZER, "42S22", "duplicate column:" + this);
                    }
                }
            } else {
                tmpFieldTable = getTableName();
                column = planNode.getOuterFields().get(new NamedField(tmpFieldTable, tmpFieldName, null));
            }
        }
        if (column != null && context.isSelectFirst()) {
            return column;
        }

        // find from inner fields
        Item columnFromMeta = null;
        if (StringUtils.isEmpty(getTableName())) {
            for (NamedField namedField : planNode.getInnerFields().keySet()) {
                if (StringUtils.equalsIgnoreCase(tmpFieldName, namedField.getName())) {
                    if (columnFromMeta == null) {
                        tmpFieldTable = namedField.getTable();
                        NamedField coutField = planNode.getInnerFields().get(new NamedField(tmpFieldTable, tmpFieldName, null));
                        this.tableName = namedField.getTable();
                        getReferTables().clear();
                        this.getReferTables().add(coutField.planNode);
                        columnFromMeta = this;
                    } else {
                        if (planNode.type() == PlanNodeType.JOIN) {
                            JoinNode jn = (JoinNode) planNode;
                            if (jn.getUsingFields() != null && jn.getUsingFields().contains(columnFromMeta.getItemName().toLowerCase())) {
                                continue;
                            }
                        }
                        throw new MySQLOutPutException(ErrorCode.ER_OPTIMIZER, "42S22", "duplicate column:" + this);
                    }
                }
            }
        } else {
            tmpFieldTable = getTableName();
            NamedField tmpField = new NamedField(tmpFieldTable, tmpFieldName, null);
            if (planNode.getInnerFields().containsKey(tmpField)) {
                NamedField coutField = planNode.getInnerFields().get(tmpField);
                getReferTables().clear();
                getReferTables().add(coutField.planNode);
                this.tableName = tmpField.getTable();
                columnFromMeta = this;
            }
        }
        if (columnFromMeta != null) {
            return columnFromMeta;
        } else if (column == null)
            throw new MySQLOutPutException(ErrorCode.ER_OPTIMIZER, "42S22", "column " + this + " not found");
        else {
            return column;
        }

    }

    private Item getMergeNodeColumn(String tmpFieldTable, String tmpFieldName, PlanNode planNode) {
        // select union only found in outerfields
        Item column;
        if (StringUtils.isEmpty(getTableName())) {
            PlanNode firstNode = planNode.getChild();
            boolean found = false;
            for (NamedField coutField : firstNode.getOuterFields().keySet()) {
                if (tmpFieldName.equalsIgnoreCase(coutField.getName())) {
                    if (!found) {
                        tmpFieldTable = coutField.getTable();
                        found = true;
                    } else {
                        throw new MySQLOutPutException(ErrorCode.ER_BAD_FIELD_ERROR, "(42S22",
                                "Unknown column '" + tmpFieldName + "' in 'order clause'");
                    }
                }
            }
            column = planNode.getOuterFields().get(new NamedField(tmpFieldTable, tmpFieldName, null));
        } else {
            throw new MySQLOutPutException(ErrorCode.ER_TABLENAME_NOT_ALLOWED_HERE, "42000",
                    "Table '" + getTableName() + "' from one of the SELECTs cannot be used in global ORDER clause");
        }
        return column;
    }

    @Override
    public void fixRefer(ReferContext context) {
        if (isWild())
            return;
        PlanNode node = context.getPlanNode();
        PlanNode tn = getReferTables().iterator().next();
        node.addSelToReferedMap(tn, this);
    }

    @Override
    public SQLExpr toExpression() {
        SQLIdentifierExpr parent = StringUtil.isEmpty(tableName) ? null : new SQLIdentifierExpr(tableName);
        if (parent != null) {
            return new SQLPropertyExpr(parent, itemName);
        } else return new SQLIdentifierExpr(itemName);
    }

    @Override
    protected Item cloneStruct(boolean forCalculate, List<Item> calArgs, boolean isPushDown, List<Field> fields) {
        return new ItemField(dbName, tableName, itemName);
    }

    public Field getField() {
        return field;
    }
}
