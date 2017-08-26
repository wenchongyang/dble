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
package io.mycat.config;

import io.mycat.MycatServer;
import io.mycat.backend.datasource.PhysicalDBNode;
import io.mycat.backend.datasource.PhysicalDBPool;
import io.mycat.backend.datasource.PhysicalDatasource;
import io.mycat.backend.mysql.nio.MySQLDataSource;
import io.mycat.config.loader.SchemaLoader;
import io.mycat.config.loader.xml.XMLConfigLoader;
import io.mycat.config.loader.xml.XMLSchemaLoader;
import io.mycat.config.model.*;
import io.mycat.config.util.ConfigException;
import io.mycat.route.sequence.handler.DistributedSequenceHandler;
import io.mycat.route.sequence.handler.IncrSequenceMySQLHandler;
import io.mycat.route.sequence.handler.IncrSequenceTimeHandler;
import io.mycat.route.sequence.handler.IncrSequenceZKHandler;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.*;

/**
 * @author mycat
 */
public class ConfigInitializer {

    private static final Logger LOGGER = Logger.getLogger(ConfigInitializer.class);

    private volatile SystemConfig system;
    private volatile FirewallConfig firewall;
    private volatile Map<String, UserConfig> users;
    private volatile Map<String, SchemaConfig> schemas;
    private volatile Map<String, PhysicalDBNode> dataNodes;
    private volatile Map<String, PhysicalDBPool> dataHosts;
    private volatile Map<ERTable, Set<ERTable>> erRelations;


    private volatile boolean dataHostWithoutWH = false;

    public ConfigInitializer(boolean loadDataHost) {
        //load server.xml
        XMLConfigLoader configLoader = new XMLConfigLoader();
        //load rule.xml and schema.xml
        SchemaLoader schemaLoader = new XMLSchemaLoader(configLoader.getSystemConfig().isLowerCaseTableNames());
        this.system = configLoader.getSystemConfig();
        this.users = configLoader.getUserConfigs();
        this.schemas = schemaLoader.getSchemas();
        this.erRelations = schemaLoader.getErRelations();
        // need reload DataHost and DataNode?
        if (loadDataHost) {
            this.dataHosts = initDataHosts(schemaLoader);
            this.dataNodes = initDataNodes(schemaLoader);
        } else {
            this.dataHosts = MycatServer.getInstance().getConfig().getDataHosts();
            this.dataNodes = MycatServer.getInstance().getConfig().getDataNodes();
            //add the flag into the reload
            this.dataHostWithoutWH = MycatServer.getInstance().getConfig().isDataHostWithoutWR();
        }

        this.firewall = configLoader.getFirewallConfig();

        //load global sequence
        if (system.getSequnceHandlerType() == SystemConfig.SEQUENCE_HANDLER_MYSQL) {
            IncrSequenceMySQLHandler.getInstance().load(system.isLowerCaseTableNames());
        }

        if (system.getSequnceHandlerType() == SystemConfig.SEQUENCE_HANDLER_LOCAL_TIME) {
            IncrSequenceTimeHandler.getInstance().load();
        }

        if (system.getSequnceHandlerType() == SystemConfig.SEQUENCE_HANDLER_ZK_DISTRIBUTED) {
            DistributedSequenceHandler.getInstance().load();
        }

        if (system.getSequnceHandlerType() == SystemConfig.SEQUENCE_HANDLER_ZK_GLOBAL_INCREMENT) {
            IncrSequenceZKHandler.getInstance().load(system.isLowerCaseTableNames());
        }

        /**
         * check config
         */
        this.selfChecking0();
    }

    private void selfChecking0() throws ConfigException {
        // check 1.user's schemas are all existed in schema's conf
        // 2.schema's conf is not empty
        if (users == null || users.isEmpty()) {
            throw new ConfigException("SelfCheck### user all node is empty!");
        } else {
            for (UserConfig uc : users.values()) {
                if (uc == null) {
                    throw new ConfigException("SelfCheck### users node within the item is empty!");
                }
                if (!uc.isManager()) {
                    Set<String> authSchemas = uc.getSchemas();
                    if (authSchemas == null) {
                        throw new ConfigException("SelfCheck### user " + uc.getName() + "refered schemas is empty!");
                    }
                    for (String schema : authSchemas) {
                        if (!schemas.containsKey(schema)) {
                            String errMsg = "SelfCheck###  schema " + schema + " refered by user " + uc.getName() + " is not exist!";
                            throw new ConfigException(errMsg);
                        }
                    }
                }
            }
        }
        Set<String> allUseDataNode = new HashSet<>();
        // check schema
        for (SchemaConfig sc : schemas.values()) {
            if (null == sc) {
                throw new ConfigException("SelfCheck### schema all node is empty!");
            } else {
                // check dataNode / dataHost
                if (this.dataNodes != null && this.dataHosts != null) {
                    Set<String> dataNodeNames = sc.getAllDataNodes();
                    for (String dataNodeName : dataNodeNames) {
                        PhysicalDBNode node = this.dataNodes.get(dataNodeName);
                        if (node == null) {
                            throw new ConfigException("SelfCheck### schema dbnode is empty!");
                        }
                    }
                    allUseDataNode.addAll(dataNodeNames);
                }
            }
        }

        deleteRedundancyConf(allUseDataNode);
        checkWriteHost();

    }

    private void checkWriteHost() {
        for (Map.Entry<String, PhysicalDBPool> pool : this.dataHosts.entrySet()) {
            if (pool.getValue().getSources() == null || pool.getValue().getSources().length == 0) {
                LOGGER.warn("dataHost " + pool.getKey() + " has no writeHost ,server will ignore it");
                this.dataHostWithoutWH = true;
            }
        }
    }

    private void deleteRedundancyConf(Set<String> allUseDataNode) {
        Set<String> allUseHost = new HashSet<>();
        //delete redundancy dataNode
        Iterator<Map.Entry<String, PhysicalDBNode>> iterator = this.dataNodes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, PhysicalDBNode> entry = iterator.next();
            String dataNodeName = entry.getKey();
            if (allUseDataNode.contains(dataNodeName)) {
                allUseHost.add(entry.getValue().getDbPool().getHostName());
            } else {
                LOGGER.warn("dataNode " + dataNodeName + " is useless,server will ignore it");
                iterator.remove();
            }
        }
        allUseDataNode.clear();
        //delete redundancy dataHost
        if (allUseHost.size() < this.dataHosts.size()) {
            Iterator<String> dataHost = this.dataHosts.keySet().iterator();
            while (dataHost.hasNext()) {
                String dataHostName = dataHost.next();
                if (!allUseHost.contains(dataHostName)) {
                    LOGGER.warn("dataHost " + dataHostName + " is useless,server will ignore it");
                    dataHost.remove();
                }
            }
        }
        allUseHost.clear();
    }

    public void testConnection() {
        if (this.dataNodes != null && this.dataHosts != null) {
            Map<String, Boolean> map = new HashMap<>();
            for (PhysicalDBNode dataNode : dataNodes.values()) {
                String database = dataNode.getDatabase();
                PhysicalDBPool pool = dataNode.getDbPool();

                for (PhysicalDatasource ds : pool.getAllDataSources()) {
                    String key = ds.getName() + "_" + database;
                    if (map.get(key) == null) {
                        map.put(key, false);
                        try {
                            boolean isConnected = ds.testConnection(database);
                            map.put(key, isConnected);
                        } catch (IOException e) {
                            LOGGER.warn("test conn " + key + " error:", e);
                        }
                    }
                }
            }
            boolean isConnectivity = true;
            for (Map.Entry<String, Boolean> entry : map.entrySet()) {
                String key = entry.getKey();
                Boolean value = entry.getValue();
                if (!value && isConnectivity) {
                    LOGGER.warn("SelfCheck### test " + key + " database connection failed ");
                    isConnectivity = false;
                } else {
                    LOGGER.info("SelfCheck### test " + key + " database connection success ");
                }
            }
            if (!isConnectivity) {
                throw new ConfigException("SelfCheck### there are some datasource connection failed, pls check!");
            }
        }
    }

    public SystemConfig getSystem() {
        return system;
    }


    public FirewallConfig getFirewall() {
        return firewall;
    }

    public Map<String, UserConfig> getUsers() {
        return users;
    }

    public Map<String, SchemaConfig> getSchemas() {
        return schemas;
    }

    public Map<String, PhysicalDBNode> getDataNodes() {
        return dataNodes;
    }

    public Map<String, PhysicalDBPool> getDataHosts() {
        return this.dataHosts;
    }

    public Map<ERTable, Set<ERTable>> getErRelations() {
        return erRelations;
    }

    private Map<String, PhysicalDBPool> initDataHosts(SchemaLoader schemaLoader) {
        Map<String, DataHostConfig> nodeConfs = schemaLoader.getDataHosts();
        //create PhysicalDBPool according to DataHost
        Map<String, PhysicalDBPool> nodes = new HashMap<>(nodeConfs.size());
        for (DataHostConfig conf : nodeConfs.values()) {
            //create PhysicalDBPool
            PhysicalDBPool pool = getPhysicalDBPool(conf);
            nodes.put(pool.getHostName(), pool);
        }
        return nodes;
    }

    private PhysicalDatasource[] createDataSource(DataHostConfig conf, DBHostConfig[] nodes,
                                                  boolean isRead) {
        PhysicalDatasource[] dataSources = new PhysicalDatasource[nodes.length];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i].setIdleTimeout(system.getIdleTimeout());
            MySQLDataSource ds = new MySQLDataSource(nodes[i], conf, isRead);
            dataSources[i] = ds;
        }
        return dataSources;
    }

    private PhysicalDBPool getPhysicalDBPool(DataHostConfig conf) {
        //create PhysicalDatasource for write host
        PhysicalDatasource[] writeSources = createDataSource(conf, conf.getWriteHosts(), false);
        Map<Integer, DBHostConfig[]> readHostsMap = conf.getReadHosts();
        Map<Integer, PhysicalDatasource[]> readSourcesMap = new HashMap<>(readHostsMap.size());
        //create map for read host:writeHost-> readHost's  PhysicalDatasource[]
        for (Map.Entry<Integer, DBHostConfig[]> entry : readHostsMap.entrySet()) {
            PhysicalDatasource[] readSources = createDataSource(conf, entry.getValue(), true);
            readSourcesMap.put(entry.getKey(), readSources);
        }

        PhysicalDBPool pool = new PhysicalDBPool(conf.getName(), conf, writeSources, readSourcesMap, conf.getBalance());
        return pool;
    }

    private Map<String, PhysicalDBNode> initDataNodes(SchemaLoader schemaLoader) {
        Map<String, DataNodeConfig> nodeConfs = schemaLoader.getDataNodes();
        Map<String, PhysicalDBNode> nodes = new HashMap<>(nodeConfs.size());
        for (DataNodeConfig conf : nodeConfs.values()) {
            PhysicalDBPool pool = this.dataHosts.get(conf.getDataHost());
            if (pool == null) {
                throw new ConfigException("dataHost not exists " + conf.getDataHost());
            }
            PhysicalDBNode dataNode = new PhysicalDBNode(conf.getName(), conf.getDatabase(), pool);
            nodes.put(dataNode.getName(), dataNode);
        }
        return nodes;
    }


    public boolean isDataHostWithoutWH() {
        return dataHostWithoutWH;
    }

}
