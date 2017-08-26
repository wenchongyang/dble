package io.mycat.config.loader.zkprocess.comm;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ZookeeperProcessListen
 *
 * @author liujun
 * @date 2015/2/4
 * @vsersion 0.0.1
 */
public class ZookeeperProcessListen {

    private static final Logger LOGGER = LoggerFactory.getLogger(ZookeeperProcessListen.class);

    private Set<NotifyService> initCache = new HashSet<>();
    private Map<String, NotifyService> watchMap = new HashMap<>();

    public void addToInit(NotifyService service) {
        initCache.add(service);
    }

    public void clearInited() {
        initCache.clear();
    }

    public void addWatch(String key, NotifyService service) {
        watchMap.put(key, service);
    }

    public Set<String> getWatchPath() {
        return watchMap.keySet();
    }

    public boolean notify(String key) {
        boolean result = false;
        if (null != key && !"".equals(key)) {
            NotifyService cacheService = watchMap.get(key);
            if (null != cacheService) {
                try {
                    result = cacheService.notifyProcess();
                } catch (Exception e) {
                    LOGGER.error("ZookeeperProcessListen notify key :" + key + " error:Exception info:", e);
                }
            }
        }
        return result;
    }

    public void initAllNode() {
        Iterator<NotifyService> notifyIter = initCache.iterator();
        NotifyService item;
        while (notifyIter.hasNext()) {
            item = notifyIter.next();
            try {
                item.notifyProcess();
            } catch (Exception e) {
                LOGGER.error("ZookeeperProcessListen initAllNode :" + item + ";error:Exception info:", e);
            }
        }
    }

}
