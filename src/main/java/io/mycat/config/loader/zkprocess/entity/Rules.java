package io.mycat.config.loader.zkprocess.entity;

import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;

import io.mycat.config.Versions;
import io.mycat.config.loader.zkprocess.entity.rule.function.Function;
import io.mycat.config.loader.zkprocess.entity.rule.tablerule.TableRule;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(namespace = "http://io." + Versions.ROOT_PREFIX + "/", name = "rule")
public class Rules {

    protected List<TableRule> tableRule;

    protected List<Function> function;

    public List<TableRule> getTableRule() {
        if (this.tableRule == null) {
            tableRule = new ArrayList<>();
        }
        return tableRule;
    }

    public void setTableRule(List<TableRule> tableRule) {
        this.tableRule = tableRule;
    }

    public List<Function> getFunction() {
        if (this.function == null) {
            function = new ArrayList<>();
        }
        return function;
    }

    public void setFunction(List<Function> function) {
        this.function = function;
    }

    @Override
    public String toString() {
        String builder = "Rules [tableRule=" +
                tableRule +
                ", function=" +
                function +
                "]";
        return builder;
    }


}
