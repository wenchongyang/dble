package io.mycat.config.loader.zkprocess.console;

/**
 * ParseParamEnum
 *
 *
 * author:liujun
 * Created:2016/9/18
 *
 *
 *
 *
 */
public enum ParseParamEnum {

    /**
     * mapfile for rule
     *
     *
     */
    ZK_PATH_RULE_MAPFILE_NAME("mapFile"),;

    private String key;

    ParseParamEnum(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

}
