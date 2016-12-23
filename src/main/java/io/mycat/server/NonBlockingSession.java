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
package io.mycat.server;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mycat.MycatServer;
import io.mycat.backend.BackendConnection;
import io.mycat.backend.datasource.PhysicalDBNode;
import io.mycat.backend.mysql.nio.MySQLConnection;
import io.mycat.backend.mysql.nio.handler.KillConnectionHandler;
import io.mycat.backend.mysql.nio.handler.LockTablesHandler;
import io.mycat.backend.mysql.nio.handler.MultiNodeQueryHandler;
import io.mycat.backend.mysql.nio.handler.ResponseHandler;
import io.mycat.backend.mysql.nio.handler.SingleNodeHandler;
import io.mycat.backend.mysql.nio.handler.UnLockTablesHandler;
import io.mycat.backend.mysql.nio.handler.transaction.CommitNodesHandler;
import io.mycat.backend.mysql.nio.handler.transaction.RollbackNodesHandler;
import io.mycat.backend.mysql.nio.handler.transaction.normal.NormalCommitNodesHandler;
import io.mycat.backend.mysql.nio.handler.transaction.normal.NormalRollbackNodesHandler;
import io.mycat.backend.mysql.nio.handler.transaction.xa.XACommitNodesHandler;
import io.mycat.backend.mysql.nio.handler.transaction.xa.XARollbackNodesHandler;
import io.mycat.backend.mysql.xa.TxState;
import io.mycat.config.ErrorCode;
import io.mycat.config.MycatConfig;
import io.mycat.net.FrontendConnection;
import io.mycat.net.mysql.OkPacket;
import io.mycat.route.RouteResultset;
import io.mycat.route.RouteResultsetNode;

/**
 * @author mycat
 */
public class NonBlockingSession implements Session {

    public static final Logger LOGGER = LoggerFactory.getLogger(NonBlockingSession.class);

    private final ServerConnection source;
    private final ConcurrentHashMap<RouteResultsetNode, BackendConnection> target;
    // life-cycle: each sql execution
    private volatile SingleNodeHandler singleNodeHandler;
    private volatile MultiNodeQueryHandler multiNodeHandler;
    private RollbackNodesHandler rollbackHandler;
    private CommitNodesHandler commitHandler;
    private volatile String xaTXID;
    private volatile TxState xaState;
	private boolean prepared;

    public NonBlockingSession(ServerConnection source) {
        this.source = source;
        this.target = new ConcurrentHashMap<RouteResultsetNode, BackendConnection>(2, 0.75f);
    }

    @Override
    public ServerConnection getSource() {
        return source;
    }

    @Override
    public int getTargetCount() {
        return target.size();
    }

    public Set<RouteResultsetNode> getTargetKeys() {
        return target.keySet();
    }

    public BackendConnection getTarget(RouteResultsetNode key) {
        return target.get(key);
    }

    public Map<RouteResultsetNode, BackendConnection> getTargetMap() {
        return this.target;
    }

    public TxState getXaState() {
		return xaState;
	}

	public void setXaState(TxState xaState) {
		this.xaState = xaState;
	}

    
    @Override
    public void execute(RouteResultset rrs, int type) {
        // clear prev execute resources
        clearHandlesResources();
        if (LOGGER.isDebugEnabled()) {
            StringBuilder s = new StringBuilder();
            LOGGER.debug(s.append(source).append(rrs).toString() + " rrs ");
        }
        // 检查路由结果是否为空
        RouteResultsetNode[] nodes = rrs.getNodes();
        if (nodes == null || nodes.length == 0 || nodes[0].getName() == null || nodes[0].getName().equals("")) {
            source.writeErrMessage(ErrorCode.ER_NO_DB_ERROR,
                    "No dataNode found ,please check tables defined in schema:" + source.getSchema());
            return;
        }
		if (this.getSessionXaID() != null && this.xaState == TxState.TX_INITIALIZE_STATE) {
			this.xaState = TxState.TX_STARTED_STATE;
		}
		if (nodes.length == 1) {
			singleNodeHandler = new SingleNodeHandler(rrs, this);
			if (this.isPrepared()) {
				singleNodeHandler.setPrepared(true);
			}
			try {
				singleNodeHandler.execute();
			} catch (Exception e) {
				LOGGER.warn(new StringBuilder().append(source).append(rrs).toString(), e);
				source.writeErrMessage(ErrorCode.ERR_HANDLE_DATA, e.toString());
			}
		} else {
			multiNodeHandler = new MultiNodeQueryHandler(type, rrs, this);
			if (this.isPrepared()) {
				multiNodeHandler.setPrepared(true);
			}
			try {
				multiNodeHandler.execute();
			} catch (Exception e) {
				LOGGER.warn(new StringBuilder().append(source).append(rrs).toString(), e);
				source.writeErrMessage(ErrorCode.ERR_HANDLE_DATA, e.toString());
			}
		}

		if (this.isPrepared()) {
			this.setPrepared(false);
		}
    }

    private CommitNodesHandler createCommitNodesHandler() {
		if (commitHandler == null) {
			if (this.getSessionXaID() == null) {
				commitHandler = new NormalCommitNodesHandler(this);
			} else {
				commitHandler = new XACommitNodesHandler(this);
			}
		} else {
			if (this.getSessionXaID() == null && (commitHandler instanceof XACommitNodesHandler)) {
				commitHandler = new NormalCommitNodesHandler(this);
			}
			if (this.getSessionXaID() != null && (commitHandler instanceof NormalCommitNodesHandler)) {
				commitHandler = new XACommitNodesHandler(this);
			}
		}
		return commitHandler;
	}

	public void commit() {
		final int initCount = target.size();
		if (initCount <= 0) {
			clearResources(false);
			ByteBuffer buffer = source.allocate();
			buffer = source.writeToBuffer(OkPacket.OK, buffer);
			source.write(buffer);
			return;
		}

		createCommitNodesHandler();
		commitHandler.commit();
	}
    	
	private RollbackNodesHandler createRollbackNodesHandler() {
		if (rollbackHandler == null) {
			if (this.getSessionXaID() == null) {
				rollbackHandler = new NormalRollbackNodesHandler(this);
			} else {
				rollbackHandler = new XARollbackNodesHandler(this);
			}
		} else {
			if (this.getSessionXaID() == null && (rollbackHandler instanceof XARollbackNodesHandler)) {
				rollbackHandler = new NormalRollbackNodesHandler(this);
			}
			if (this.getSessionXaID() != null && (rollbackHandler instanceof NormalRollbackNodesHandler)) {
				rollbackHandler = new XARollbackNodesHandler(this);
			}
		}
		return rollbackHandler;
	}

	public void rollback() {
		final int initCount = target.size();
		if (initCount <= 0) {
			if (LOGGER.isDebugEnabled()) {
				LOGGER.debug("no session bound connections found ,no need send rollback cmd ");
			}
			clearResources(false);
			ByteBuffer buffer = source.allocate();
			buffer = source.writeToBuffer(OkPacket.OK, buffer);
			source.write(buffer);
			return;
		}
		createRollbackNodesHandler();
		rollbackHandler.rollback();
	}

	/**
	 * 执行lock tables语句方法
	 * @author songdabin
	 * @date 2016-7-9
	 * @param rrs
	 */
	public void lockTable(RouteResultset rrs) {
		// 检查路由结果是否为空
		RouteResultsetNode[] nodes = rrs.getNodes();
		if (nodes == null || nodes.length == 0 || nodes[0].getName() == null
				|| nodes[0].getName().equals("")) {
			source.writeErrMessage(ErrorCode.ER_NO_DB_ERROR,
					"No dataNode found ,please check tables defined in schema:"
							+ source.getSchema());
			return;
		}
		LockTablesHandler handler = new LockTablesHandler(this, rrs);
		source.setLocked(true);
		try {
			handler.execute();
		} catch (Exception e) {
			LOGGER.warn(new StringBuilder().append(source).append(rrs).toString(), e);
			source.writeErrMessage(ErrorCode.ERR_HANDLE_DATA, e.toString());
		}
	}

	/**
	 * 执行unlock tables语句方法
	 * @author songdabin
	 * @date 2016-7-9
	 * @param rrs
	 */
	public void unLockTable(String sql) {
		UnLockTablesHandler handler = new UnLockTablesHandler(this, this.source.isAutocommit(), sql);
		handler.execute();
	}
	
    @Override
    public void cancel(FrontendConnection sponsor) {

    }

    /**
     * {@link ServerConnection#isClosed()} must be true before invoking this
     */
    public void terminate() {
        closeAndClearResources("client closed ");
    }

    public void closeAndClearResources(String reason) {
		if (source.isTxstart()) {
			if (this.getXaState() == null || this.getXaState() != TxState.TX_INITIALIZE_STATE) {
				return;
			}
		}
        for (BackendConnection node : target.values()) {
            node.close(reason);
        }
        target.clear();
        clearHandlesResources();
    }

    public void releaseConnectionIfSafe(BackendConnection conn, boolean debug, boolean needRollBack) {
        RouteResultsetNode node = (RouteResultsetNode) conn.getAttachment();
        if (node != null) {
            if (node.isDisctTable()) {
                return;
            }
            if ((this.source.isAutocommit() || conn.isFromSlaveDB())&&!this.source.isTxstart() && !this.source.isLocked()) {
                releaseConnection((RouteResultsetNode) conn.getAttachment(), LOGGER.isDebugEnabled(), needRollBack);
            }
        }
    }

    public void releaseConnection(RouteResultsetNode rrn, boolean debug, final boolean needRollback) {

        BackendConnection c = target.remove(rrn);
        if (c != null) {
            if (debug) {
                LOGGER.debug("release connection " + c);
            }
            if (c.getAttachment() != null) {
                c.setAttachment(null);
            }
			if (!c.isClosedOrQuit()) {
				if (c.isAutocommit()) {
					c.release();
				} else if (needRollback) {
//					c.setResponseHandler(new RollbackReleaseHandler());
//					c.rollback();
					c.quit();
				} else {
					c.release();
				}

            }
        }
    }

    public void releaseConnections(final boolean needRollback) {
        boolean debug = LOGGER.isDebugEnabled();
        for (RouteResultsetNode rrn : target.keySet()) {
            releaseConnection(rrn, debug, needRollback);
        }
    }

    public void releaseConnection(BackendConnection con) {
        Iterator<Entry<RouteResultsetNode, BackendConnection>> itor = target
                .entrySet().iterator();
        while (itor.hasNext()) {
            BackendConnection theCon = itor.next().getValue();
            if (theCon == con) {
                itor.remove();
                con.release();
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("realse connection " + con);
                }
                break;
            }
        }

    }

    /**
     * @return previous bound connection
     */
    public BackendConnection bindConnection(RouteResultsetNode key,
                                            BackendConnection conn) {
        // System.out.println("bind connection "+conn+
        // " to key "+key.getName()+" on sesion "+this);
        return target.put(key, conn);
    }
    
    public boolean tryExistsCon(final BackendConnection conn, RouteResultsetNode node) {
        if (conn == null) {
            return false;
        }

        boolean canReUse = false;
        // conn 是 slave db 的，并且 路由结果显示，本次sql可以重用该 conn
        if (conn.isFromSlaveDB() && (node.canRunnINReadDB(getSource().isAutocommit())
                && (node.getRunOnSlave() == null || node.getRunOnSlave()))) {
            canReUse = true;
        }

        // conn 是 master db 的，并且路由结果显示，本次sql可以重用该conn
        if (!conn.isFromSlaveDB() && (node.getRunOnSlave() == null || !node.getRunOnSlave())) {
            canReUse = true;
        }

        if (canReUse) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("found connections in session to use " + conn
                        + " for " + node);
            }
            conn.setAttachment(node);
            return true;
        } else {
            // slavedb connection and can't use anymore ,release it
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("release slave connection,can't be used in trasaction  "
                        + conn + " for " + node);
            }
            releaseConnection(node, LOGGER.isDebugEnabled(), false);
        }
        return false;
    }

//	public boolean tryExistsCon(final BackendConnection conn,
//			RouteResultsetNode node) {
//
//		if (conn == null) {
//			return false;
//		}
//		if (!conn.isFromSlaveDB()
//				|| node.canRunnINReadDB(getSource().isAutocommit())) {
//			if (LOGGER.isDebugEnabled()) {
//				LOGGER.debug("found connections in session to use " + conn
//						+ " for " + node);
//			}
//			conn.setAttachment(node);
//			return true;
//		} else {
//			// slavedb connection and can't use anymore ,release it
//			if (LOGGER.isDebugEnabled()) {
//				LOGGER.debug("release slave connection,can't be used in trasaction  "
//						+ conn + " for " + node);
//			}
//			releaseConnection(node, LOGGER.isDebugEnabled(), false);
//		}
//		return false;
//	}

    protected void kill() {
        boolean hooked = false;
        AtomicInteger count = null;
        Map<RouteResultsetNode, BackendConnection> killees = null;
        for (RouteResultsetNode node : target.keySet()) {
            BackendConnection c = target.get(node);
            if (c != null) {
                if (!hooked) {
                    hooked = true;
                    killees = new HashMap<RouteResultsetNode, BackendConnection>();
                    count = new AtomicInteger(0);
                }
                killees.put(node, c);
                count.incrementAndGet();
            }
        }
        if (hooked) {
            for (Entry<RouteResultsetNode, BackendConnection> en : killees
                    .entrySet()) {
                KillConnectionHandler kill = new KillConnectionHandler(
                        en.getValue(), this);
                MycatConfig conf = MycatServer.getInstance().getConfig();
                PhysicalDBNode dn = conf.getDataNodes().get(
                        en.getKey().getName());
                try {
                    dn.getConnectionFromSameSource(null, true, en.getValue(),
                            kill, en.getKey());
                } catch (Exception e) {
                    LOGGER.error(
                            "get killer connection failed for " + en.getKey(),
                            e);
                    kill.connectionError(e, null);
                }
            }
        }
    }

    private void clearHandlesResources() {
        SingleNodeHandler singleHander = singleNodeHandler;
        if (singleHander != null) {
            singleHander.clearResources();
            singleNodeHandler = null;
        }
        MultiNodeQueryHandler multiHandler = multiNodeHandler;
        if (multiHandler != null) {
            multiHandler.clearResources();
            multiNodeHandler = null;
        }
		if (rollbackHandler != null) {
			rollbackHandler.clearResources();
		}
		if (commitHandler != null) {
			commitHandler.clearResources();
		}
    }

    public void clearResources(final boolean needRollback) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("clear session resources " + this);
        }
        this.releaseConnections(needRollback);
        clearHandlesResources();
        source.setTxstart(false);
        source.getAndIncrementXid();
    }

    public boolean closed() {
        return source.isClosed()&&!source.isTxstart();
    }

    private String genXATXID() {
        return MycatServer.getInstance().genXATXID();
    }

	public void setXATXEnabled(boolean xaTXEnabled) {
		if (xaTXEnabled && this.xaTXID == null) {
			LOGGER.info("XA Transaction enabled ,con " + this.getSource());
			xaTXID = genXATXID();
			xaState = TxState.TX_INITIALIZE_STATE;
		} else if (!xaTXEnabled && this.xaTXID != null) {
			LOGGER.info("XA Transaction disabled ,con " + this.getSource());
			xaTXID = null;
			xaState =null;
		}
	}

    public String getSessionXaID() {
        return xaTXID;
    }

    public boolean isPrepared() {
        return prepared;
    }

    public void setPrepared(boolean prepared) {
        this.prepared = prepared;
    }

	public MySQLConnection freshConn(MySQLConnection errConn, ResponseHandler queryHandler) {
		for (final RouteResultsetNode node : this.getTargetKeys()) {
			final MySQLConnection mysqlCon = (MySQLConnection) this.getTarget(node);
			if (errConn.equals(mysqlCon)) {
				MycatConfig conf = MycatServer.getInstance().getConfig();
				PhysicalDBNode dn = conf.getDataNodes().get(node.getName());
				try {
					MySQLConnection newConn = (MySQLConnection) dn.getConnection(dn.getDatabase(),errConn.isAutocommit());
					newConn.setXaStatus(errConn.getXaStatus());
					if(!newConn.setResponseHandler(queryHandler)){
						return errConn;
					}
					this.getTargetMap().put(node, newConn);
					return newConn;
				} catch (Exception e) {
					return errConn;
				}
			}
		}
		return errConn;
	}

	public MySQLConnection releaseExcept(TxState state) {
		MySQLConnection errConn = null;
		for (final RouteResultsetNode node : this.getTargetKeys()) {
			final MySQLConnection mysqlCon = (MySQLConnection) this.getTarget(node);
			if (mysqlCon.getXaStatus() != state) {
				this.releaseConnection(node, true, false);
			} else {
				errConn = mysqlCon;
			}
		}
		return errConn;
	}
}
