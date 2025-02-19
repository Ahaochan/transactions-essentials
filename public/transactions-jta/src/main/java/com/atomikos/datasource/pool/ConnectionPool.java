/**
 * Copyright (C) 2000-2020 Atomikos <info@atomikos.com>
 *
 * LICENSE CONDITIONS
 *
 * See http://www.atomikos.com/Main/WhichLicenseApplies for details.
 */

package com.atomikos.datasource.pool;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.atomikos.logging.Logger;
import com.atomikos.logging.LoggerFactory;
import com.atomikos.thread.InterruptedExceptionHelper;
import com.atomikos.thread.TaskManager;
import com.atomikos.timing.AlarmTimer;
import com.atomikos.timing.AlarmTimerListener;
import com.atomikos.timing.PooledAlarmTimer;


public abstract class ConnectionPool<ConnectionType> implements XPooledConnectionEventListener<ConnectionType>
{
	private static Logger LOGGER = LoggerFactory.createLogger(ConnectionPool.class);

	private final static int DEFAULT_MAINTENANCE_INTERVAL = 60;

	protected List<XPooledConnection<ConnectionType>> connections = new ArrayList<XPooledConnection<ConnectionType>>();
	private ConnectionFactory<ConnectionType> connectionFactory;
	private ConnectionPoolProperties properties;
	private boolean destroyed;
	private PooledAlarmTimer maintenanceTimer;
	private String name;


	public ConnectionPool ( ConnectionFactory<ConnectionType> connectionFactory , ConnectionPoolProperties properties ) throws ConnectionPoolException
	{
		this.connectionFactory = connectionFactory;
		this.properties = properties;
		this.destroyed = false;
		this.name = properties.getUniqueResourceName();
		init();
	}

	private void assertNotDestroyed() throws ConnectionPoolException
	{
		if (destroyed) throw new ConnectionPoolException ( "Pool was already destroyed - you can no longer use it" );
	}

	private void init() throws ConnectionPoolException
	{
		if ( LOGGER.isTraceEnabled() ) LOGGER.logTrace ( this + ": initializing..." );
		addConnectionsIfMinPoolSizeNotReached();
		launchMaintenanceTimer();
	}

	private void launchMaintenanceTimer() {
		int maintenanceInterval = properties.getMaintenanceInterval();
		if ( maintenanceInterval <= 0 ) {
			if ( LOGGER.isTraceEnabled() ) LOGGER.logTrace ( this + ": using default maintenance interval..." );
			maintenanceInterval = DEFAULT_MAINTENANCE_INTERVAL;
		}
		maintenanceTimer = new PooledAlarmTimer ( maintenanceInterval * 1000 );
		maintenanceTimer.addAlarmTimerListener(new AlarmTimerListener() {
			public void alarm(AlarmTimer timer) {
				reapPool();
				removeConnectionsThatExceededMaxLifetime();
				addConnectionsIfMinPoolSizeNotReached();
				removeIdleConnectionsIfMinPoolSizeExceeded();
			}
		});
		TaskManager.SINGLETON.executeTask ( maintenanceTimer );
	}

	private synchronized void addConnectionsIfMinPoolSizeNotReached() {
		int connectionsToAdd = properties.getMinPoolSize() - totalSize();
		for ( int i = 0 ; i < connectionsToAdd ; i++ ) {
			try {
				XPooledConnection<ConnectionType> xpc = createPooledConnection();
				connections.add ( xpc );
				xpc.registerXPooledConnectionEventListener ( this );
			} catch ( Exception dbDown ) {
				//see case 26380
				if ( LOGGER.isTraceEnabled() ) LOGGER.logTrace ( this + ": could not establish initial connection" , dbDown );
			}
		}
	}

	private XPooledConnection<ConnectionType> createPooledConnection()
			throws CreateConnectionException {
		XPooledConnection<ConnectionType> xpc = connectionFactory.createPooledConnection();
		return xpc;
	}

	protected abstract ConnectionType recycleConnectionIfPossible() throws Exception;

	/**
	 * Borrows a connection from the pool.
	 * @return The connection
	 * @throws CreateConnectionException If the pool attempted to grow but failed.
	 * @throws PoolExhaustedException If the pool could not grow because it is exhausted.
	 * @throws ConnectionPoolException Other errors.
	 */
	public ConnectionType borrowConnection() throws CreateConnectionException , PoolExhaustedException, ConnectionPoolException
	{
		assertNotDestroyed();

		ConnectionType ret = null;
		// 获取一个已经存在的打开的连接
		ret = findExistingOpenConnectionForCallingThread();	
		if (ret == null) {
			// 如果没有, 就阻塞等待获取一个可用的连接, 拿不到就会连接超时
			// 第一次进来是获取不到连接的, ret为null
			ret = findOrWaitForAnAvailableConnection();		
		}
		return ret;
	}

	private ConnectionType findOrWaitForAnAvailableConnection() throws ConnectionPoolException {
		ConnectionType ret = null;
		long remainingTime = properties.getBorrowConnectionTimeout() * 1000L;		
		do {
			// 去获取连接
			ret = retrieveFirstAvailableConnectionAndGrowPoolIfNecessary();
			if ( ret == null ) {
				// 阻塞等待可用连接, 返回还剩多少时间, 用waitTime减去等待时间
				remainingTime = waitForAtLeastOneAvailableConnection(remainingTime);
				assertNotDestroyed();
			}
		} while ( ret == null );
		return ret;
	}

	private ConnectionType retrieveFirstAvailableConnectionAndGrowPoolIfNecessary() throws CreateConnectionException {

		// retrieveFirstAvailableConnection交给子类ConnectionPoolWithConcurrentValidation实现
		ConnectionType ret = retrieveFirstAvailableConnection();
		if ( ret == null && canGrow() ) {
			// 如果获取不到连接, 就去扩展连接数量, 然后用这些原生连接去创建代理
			growPool();
			ret = retrieveFirstAvailableConnection();
		}		
		return ret;
	}

	private ConnectionType findExistingOpenConnectionForCallingThread() {
		ConnectionType recycledConnection = null ;
		try {
			recycledConnection = recycleConnectionIfPossible();
		} catch (Exception e) {
			//ignore but log
			LOGGER.logDebug ( this + ": error while trying to recycle" , e );
		}
		return recycledConnection;
	}

	protected void logCurrentPoolSize() {
		if ( LOGGER.isTraceEnabled() )  {
			LOGGER.logTrace( this +  ": current size: " + availableSize() + "/" + totalSize());
		}
	}

	private boolean canGrow() {
		return totalSize() < properties.getMaxPoolSize();
	}

	protected abstract ConnectionType retrieveFirstAvailableConnection();

	private synchronized void growPool() throws CreateConnectionException {
		XPooledConnection<ConnectionType> xpc = createPooledConnection();
		connections.add ( xpc );
		xpc.registerXPooledConnectionEventListener(this);
		logCurrentPoolSize();
	}

	private synchronized void removeIdleConnectionsIfMinPoolSizeExceeded() {
		if (connections == null || properties.getMaxIdleTime() <= 0 )
			return;

		if ( LOGGER.isTraceEnabled() ) LOGGER.logTrace ( this + ": trying to shrink pool" );
		List<XPooledConnection<ConnectionType>> connectionsToRemove = new ArrayList<XPooledConnection<ConnectionType>>();
		int maxConnectionsToRemove = totalSize() - properties.getMinPoolSize();
		if ( maxConnectionsToRemove > 0 ) {
			for ( int i=0 ; i < connections.size() ; i++ ) {
				XPooledConnection<ConnectionType> xpc = connections.get(i);
				long lastRelease = xpc.getLastTimeReleased();
				long maxIdle = properties.getMaxIdleTime();
				long now = System.currentTimeMillis();
				if ( LOGGER.isTraceEnabled() ) LOGGER.logTrace ( this + ": connection idle for " + (now - lastRelease) + "ms");
				if ( xpc.isAvailable() &&  ( (now - lastRelease) >= (maxIdle * 1000L) ) && ( connectionsToRemove.size() < maxConnectionsToRemove ) ) {
					if ( LOGGER.isTraceEnabled() ) LOGGER.logTrace ( this + ": connection idle for more than " + maxIdle + "s, closing it: " + xpc);
					destroyPooledConnection(xpc, false);
					connectionsToRemove.add(xpc);
				}
			}
		}
		connections.removeAll(connectionsToRemove);
		logCurrentPoolSize();
	}

	protected void destroyPooledConnection(XPooledConnection<ConnectionType> xpc, boolean reap) {
		xpc.destroy(reap);
	}

	public synchronized void reapPool()
	{
		long maxInUseTime = properties.getReapTimeout();
		if ( connections == null || maxInUseTime <= 0 ) return;

		if ( LOGGER.isTraceEnabled() ) LOGGER.logTrace ( this + ": reaping old connections" );

		Iterator<XPooledConnection<ConnectionType>> it = connections.iterator();
		while ( it.hasNext() ) {
			XPooledConnection<ConnectionType> xpc = it.next();
			long lastTimeReleased = xpc.getLastTimeAcquired();
			boolean inUse = !xpc.isAvailable();

			long now = System.currentTimeMillis();
			if ( inUse && ( ( now - maxInUseTime * 1000 ) > lastTimeReleased ) ) {
				if ( LOGGER.isTraceEnabled() ) LOGGER.logTrace ( this + ": connection in use for more than " + maxInUseTime + "s, reaping it: " + xpc );
				xpc.destroy(true);
				it.remove();
			}
		}
		logCurrentPoolSize();
	}
	
	private synchronized void removeConnectionsThatExceededMaxLifetime()
	{
		long maxLifetime = properties.getMaxLifetime() * 1000L;
		if ( connections == null || maxLifetime <= 0 ) return;

		if ( LOGGER.isTraceEnabled() ) LOGGER.logTrace ( this + ": closing connections that exceeded maxLifetime" );

		Iterator<XPooledConnection<ConnectionType>> it = connections.iterator();
		long now = System.currentTimeMillis();
		while ( it.hasNext() ) {
			XPooledConnection<ConnectionType> xpc = it.next();
			long creationTime = xpc.getCreationTime();
			if ( xpc.isAvailable() ) {
				if ((now - creationTime) >= (maxLifetime)) {
					if ( LOGGER.isTraceEnabled() ) LOGGER.logTrace ( this + ": connection in use for more than maxLifetime, destroying it: " + xpc );
					destroyPooledConnection(xpc, false);
					it.remove();
				}
			}
		}
		logCurrentPoolSize();
	}

	public synchronized void destroy()
	{

		if ( ! destroyed ) {
			LOGGER.logInfo ( this + ": destroying pool..." );
			for ( int i=0 ; i < connections.size() ; i++ ) {
				XPooledConnection<ConnectionType> xpc =  connections.get(i);
				if ( !xpc.isAvailable() ) {
					LOGGER.logWarning ( this + ": connection is still in use on pool destroy: " + xpc +
					" - please check your shutdown sequence to avoid heuristic termination " +
					"of ongoing transactions!" );
				}
				destroyPooledConnection(xpc, false);
			}
			connections = null;
			destroyed = true;
			maintenanceTimer.stopTimer();
			if ( LOGGER.isTraceEnabled() ) LOGGER.logTrace ( this + ": pool destroyed." );
		}
	}
	
	public synchronized void refresh() {
		List<XPooledConnection<ConnectionType>> connectionsToRemove = new ArrayList<XPooledConnection<ConnectionType>>();
		for (XPooledConnection<ConnectionType> conn : connections) {
			if (conn.isAvailable()) {
				connectionsToRemove.add(conn);
				destroyPooledConnection(conn, false);
			}
		}
		connections.removeAll(connectionsToRemove);
		addConnectionsIfMinPoolSizeNotReached();
	}

	/**
	 * Wait until the connection pool contains an available connection or a timeout happens.
	 * Returns immediately if the pool already contains a connection in state available.
	 * @throws CreateConnectionException if a timeout happened while waiting for a connection
	 */
	private synchronized long waitForAtLeastOneAvailableConnection(long waitTime) throws PoolExhaustedException
	{
		// 有可用连接就不轮询了
        while (availableSize() == 0) {
        	if ( waitTime <= 0 ) throw new PoolExhaustedException ( "ConnectionPool: pool is empty - increase either maxPoolSize or borrowConnectionTimeout" );
            long before = System.currentTimeMillis();
        	try {
        		if ( LOGGER.isTraceEnabled() ) LOGGER.logTrace ( this + ": about to wait for connection during " + waitTime + "ms...");
				// 等待一段时间 waitTime 毫秒
        		this.wait (waitTime);

			} catch (InterruptedException ex) {
				// cf bug 67457
				InterruptedExceptionHelper.handleInterruptedException ( ex );
				// ignore
				if ( LOGGER.isTraceEnabled() ) LOGGER.logTrace ( this + ": interrupted during wait" , ex );
			}
			if ( LOGGER.isTraceEnabled() ) LOGGER.logTrace ( this + ": done waiting." );
			long now = System.currentTimeMillis();
            waitTime -= (now - before);
        }
		// 返回还剩多少时间, 用waitTime减去等待时间
        return waitTime;
	}

	/**
	 * The amount of pooled connections in state available.
	 * @return the amount of pooled connections in state available.
	 */
	public synchronized int availableSize()
	{
		int ret = 0;

		if ( !destroyed ) {
			int count = 0;
			for ( int i=0 ; i < connections.size() ; i++ ) {
				XPooledConnection<ConnectionType> xpc = connections.get(i);
				if (xpc.isAvailable()) count++;
			}
			ret = count;
		}
		return ret;
	}

	/**
	 * The total amount of pooled connections in any state.
	 * @return the total amount of pooled connections in any state
	 */
	public synchronized int totalSize()
	{
		if ( destroyed ) return 0;

		return connections.size();
	}

	public synchronized void onXPooledConnectionTerminated(XPooledConnection<ConnectionType> connection) {
		if ( LOGGER.isTraceEnabled() ) LOGGER.logTrace( this +  ": connection " + connection + " became available, notifying potentially waiting threads");
		this.notify();

	}
		
	public String toString() {
		return "atomikos connection pool '" + name + "'";
	}

}
