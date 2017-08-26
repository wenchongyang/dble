package io.mycat.config.loader.zkprocess.entity.rule.tablerule;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlType;


/**
 * *<rule>
 * * *<columns>id</columns>
 * * *<algorithm>func1</algorithm>
 * *</rule>
 *
 *
 * author:liujun
 * Created:2016/9/18
 *
 *
 *
 *
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "rule", propOrder = {"columns", "algorithm"})
public class Rule {

    protected String columns;
    protected String algorithm;

    public String getColumns() {
        return columns;
    }

    public Rule setColumns(String cols) {
        this.columns = cols;
        return this;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public Rule setAlgorithm(String alg) {
        this.algorithm = alg;
        return this;
    }

    @Override
    public String toString() {
        String builder = "Rule [columns=" +
                columns +
                ", algorithm=" +
                algorithm +
                "]";
        return builder;
    }

}
