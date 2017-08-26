/*
 * Copyright (c) 2013, OpenCloudDB/MyCAT and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software;Designed and Developed mainly by many Chinese
 * opensource volunteers. you can redistribute it and/or modify it under the
 * terms of the GNU General Public License version 2 only, as published by the
 * Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Any questions about this component can be directed to it's project Web address
 * https://code.google.com/p/opencloudb/.
 *
 */
package io.mycat.route.sequence.handler;


import io.mycat.config.loader.zkprocess.comm.ZkConfig;
import io.mycat.route.util.PropertiesUtil;
import io.mycat.util.KVPathUtil;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * zookeeper IncrSequenceZKHandler
 * file:sequence_conf.properties
 * `DB`.`TABLE`.MINID minID for a thread
 * `DB`.`TABLE`.MAXID maxID for a thread
 * `DB`.`TABLE`.CURID curID for a thread
 * the properties in file is useful the first thread,the other thread/process will read from ZK
 *
 * @author Hash Zhang
 * @version 1.0
 * @time 23:35 2016/5/6
 */
public class IncrSequenceZKHandler extends IncrSequenceHandler {
    protected static final Logger LOGGER = LoggerFactory.getLogger(IncrSequenceZKHandler.class);
    private static final String PATH = KVPathUtil.getSequencesIncrPath() + "/";
    private static final String LOCK = "/lock";
    private static final String SEQ = "/seq";
    private static final IncrSequenceZKHandler INSTANCE = new IncrSequenceZKHandler();

    public static IncrSequenceZKHandler getInstance() {
        return INSTANCE;
    }

    private ThreadLocal<Map<String, Map<String, String>>> tableParaValMapThreadLocal = new ThreadLocal<>();

    private CuratorFramework client;
    private ThreadLocal<InterProcessSemaphoreMutex> interProcessSemaphoreMutexThreadLocal = new ThreadLocal<>();
    private Properties props;

    public void load(boolean isLowerCaseTableNames) {
        props = PropertiesUtil.loadProps(FILE_NAME, isLowerCaseTableNames);
        String zkAddress = ZkConfig.getInstance().getZkURL();
        if (zkAddress == null) {
            throw new RuntimeException("please check zkURL is correct in config file \"myid.prperties\" .");
        }
        try {
            initializeZK(props, zkAddress);
        } catch (Exception e) {
            LOGGER.error("Error caught while initializing ZK:" + e.getCause());
        }
    }

    private void threadLocalLoad() throws Exception {
        Enumeration<?> enu = props.propertyNames();
        while (enu.hasMoreElements()) {
            String key = (String) enu.nextElement();
            if (key.endsWith(KEY_MIN_NAME)) {
                handle(key);
            }
        }
    }

    public void initializeZK(Properties properties, String zkAddress) throws Exception {
        this.client = CuratorFrameworkFactory.newClient(zkAddress, new ExponentialBackoffRetry(1000, 3));
        this.client.start();
        this.props = properties;
    }

    private void handle(String key) throws Exception {
        String table = key.substring(0, key.indexOf(KEY_MIN_NAME));
        InterProcessSemaphoreMutex interProcessSemaphoreMutex = interProcessSemaphoreMutexThreadLocal.get();
        if (interProcessSemaphoreMutex == null) {
            interProcessSemaphoreMutex = new InterProcessSemaphoreMutex(client, PATH + table + SEQ + LOCK);
            interProcessSemaphoreMutexThreadLocal.set(interProcessSemaphoreMutex);
        }
        Map<String, Map<String, String>> tableParaValMap = tableParaValMapThreadLocal.get();
        if (tableParaValMap == null) {
            tableParaValMap = new HashMap<>();
            tableParaValMapThreadLocal.set(tableParaValMap);
        }
        Map<String, String> paraValMap = tableParaValMap.get(table);
        if (paraValMap == null) {
            paraValMap = new ConcurrentHashMap<>();
            tableParaValMap.put(table, paraValMap);

            String seqPath = PATH + table + SEQ;

            Stat stat = this.client.checkExists().forPath(seqPath);

            if (stat == null || (stat.getDataLength() == 0)) {
                paraValMap.put(table + KEY_MIN_NAME, props.getProperty(key));
                paraValMap.put(table + KEY_MAX_NAME, props.getProperty(table + KEY_MAX_NAME));
                paraValMap.put(table + KEY_CUR_NAME, props.getProperty(table + KEY_CUR_NAME));
                try {
                    String val = props.getProperty(table + KEY_MIN_NAME);
                    client.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(PATH + table + SEQ, val.getBytes());
                } catch (Exception e) {
                    LOGGER.debug("Node exists! Maybe other instance is initializing!");
                }
            }
            fetchNextPeriod(table);
        }
    }

    @Override
    public Map<String, String> getParaValMap(String prefixName) {
        Map<String, Map<String, String>> tableParaValMap = tableParaValMapThreadLocal.get();
        if (tableParaValMap == null) {
            try {
                threadLocalLoad();
            } catch (Exception e) {
                LOGGER.error("Error caught while loding configuration within current thread:" + e.getCause());
            }
            tableParaValMap = tableParaValMapThreadLocal.get();
        }
        Map<String, String> paraValMap = tableParaValMap.get(prefixName);
        return paraValMap;
    }

    @Override
    public Boolean fetchNextPeriod(String prefixName) {
        InterProcessSemaphoreMutex interProcessSemaphoreMutex = interProcessSemaphoreMutexThreadLocal.get();
        try {
            if (interProcessSemaphoreMutex == null) {
                throw new IllegalStateException("IncrSequenceZKHandler should be loaded first!");
            }
            interProcessSemaphoreMutex.acquire();
            Map<String, Map<String, String>> tableParaValMap = tableParaValMapThreadLocal.get();
            if (tableParaValMap == null) {
                throw new IllegalStateException("IncrSequenceZKHandler should be loaded first!");
            }
            Map<String, String> paraValMap = tableParaValMap.get(prefixName);
            if (paraValMap == null) {
                throw new IllegalStateException("IncrSequenceZKHandler should be loaded first!");
            }

            if (paraValMap.get(prefixName + KEY_MAX_NAME) == null) {
                paraValMap.put(prefixName + KEY_MAX_NAME, props.getProperty(prefixName + KEY_MAX_NAME));
            }
            if (paraValMap.get(prefixName + KEY_MIN_NAME) == null) {
                paraValMap.put(prefixName + KEY_MIN_NAME, props.getProperty(prefixName + KEY_MIN_NAME));
            }
            if (paraValMap.get(prefixName + KEY_CUR_NAME) == null) {
                paraValMap.put(prefixName + KEY_CUR_NAME, props.getProperty(prefixName + KEY_CUR_NAME));
            }

            long period = Long.parseLong(paraValMap.get(prefixName + KEY_MAX_NAME)) - Long.parseLong(paraValMap.get(prefixName + KEY_MIN_NAME));
            long now = Long.parseLong(new String(client.getData().forPath(PATH + prefixName + SEQ)));
            client.setData().forPath(PATH + prefixName + SEQ, ((now + period + 1) + "").getBytes());

            paraValMap.put(prefixName + KEY_MIN_NAME, (now) + "");
            paraValMap.put(prefixName + KEY_MAX_NAME, (now + period) + "");
            paraValMap.put(prefixName + KEY_CUR_NAME, (now) - 1 + "");

        } catch (Exception e) {
            LOGGER.error("Error caught while updating period from ZK:" + e.getCause());
        } finally {
            try {
                interProcessSemaphoreMutex.release();
            } catch (Exception e) {
                LOGGER.error("Error caught while realeasing distributed lock" + e.getCause());
            }
        }
        return true;
    }

    @Override
    public Boolean updateCurIDVal(String prefixName, Long val) {
        Map<String, Map<String, String>> tableParaValMap = tableParaValMapThreadLocal.get();
        if (tableParaValMap == null) {
            throw new IllegalStateException("IncrSequenceZKHandler should be loaded first!");
        }
        Map<String, String> paraValMap = tableParaValMap.get(prefixName);
        if (paraValMap == null) {
            throw new IllegalStateException("IncrSequenceZKHandler should be loaded first!");
        }
        paraValMap.put(prefixName + KEY_CUR_NAME, val + "");
        return true;
    }
}
