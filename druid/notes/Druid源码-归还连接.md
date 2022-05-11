## Druid-归还连接

> 接第一天，初始化完成后的DruidDataSource持有一个connections holder数组，holder有个属性conn是真正的数据库连接对象，Druid的初始化的连接对象为：com.alibaba.druid.pool.DruidPooledConnection。所以调用conn.close()，实质上是调用

> com.alibaba.druid.pool.DruidPooledConnection#close

```java
public void close() throws SQLException {
        if (this.disable) {
            return;
        }
				// 拿到连接池管理holder对象，初始化this的时候holder需要通过构造方法传入
        DruidConnectionHolder holder = this.holder;
        if (holder == null) {
            if (dupCloseLogEnable) {
                LOG.error("dup close");
            }
            return;
        }
				// holder反向获取DataSource
        DruidAbstractDataSource dataSource = holder.getDataSource();
  			// 持有线程是否为当前线程
        boolean isSameThread = this.getOwnerThread() == Thread.currentThread();
				// 如果持有线程和当前线程不同，则设置开关(异步关闭连接)为true
        if (!isSameThread) {
            dataSource.setAsyncCloseConnectionEnable(true);
        }
				// 接着判断是否需要执行syncClose方法
        if (dataSource.isAsyncCloseConnectionEnable()) {
            syncClose();
            return;
        }
				// syncClose方法与下面的语义类似
  			// 这段语义我猜是conn.close方法可能会被不正确多次调用，通过原子语义保证该连接只需要被归还一次
        if (!CLOSING_UPDATER.compareAndSet(this, 0, 1)) {
            return;
        }

        try {
          // ConnectionEventListener is java.util.EventListener,事件监听 is发布订阅模式，现在看到Druid是没有listener的实现的，后续继续分析这点
            for (ConnectionEventListener listener : holder.getConnectionEventListeners()) {
                listener.connectionClosed(new ConnectionEvent(this));
            }
						// 由于没有配置扩展filters
            List<Filter> filters = dataSource.getProxyFilters();
            if (filters.size() > 0) {
                FilterChainImpl filterChain = new FilterChainImpl(dataSource);
                filterChain.dataSource_recycle(this);
            } else {
              // 所有会执行recycle，syncClose也是这样的
                recycle();
            }
        } finally {
            CLOSING_UPDATER.set(this, 0);
        }

        this.disable = true;
    }
```

> com.alibaba.druid.pool.DruidPooledConnection#recycle

```java
public void recycle() throws SQLException {
        if (this.disable) {
            return;
        }
				
        DruidConnectionHolder holder = this.holder;
        if (holder == null) {
            if (dupCloseLogEnable) {
                LOG.error("dup close");
            }
            return;
        }
				// 简单判断之后调用DataSource的recycle方法
        if (!this.abandoned) {
            DruidAbstractDataSource dataSource = holder.getDataSource();
            dataSource.recycle(this);
        }
				// 然后把通过getConnection获取到的DruidPooledConnection与连接相关属性置为null，以达到连接失效的目的
        this.holder = null;
        conn = null;
        transactionInfo = null;
        closed = true;
    }
```

> com.alibaba.druid.pool.DruidDataSource#recycle

> 简单来说就是

```java
protected void recycle(DruidPooledConnection pooledConnection) throws SQLException {
        final DruidConnectionHolder holder = pooledConnection.holder;

        if (holder == null) {
            LOG.warn("connectionHolder is null");
            return;
        }
				// 做是否需要输出日志的判断
        if (logDifferentThread //
            && (!isAsyncCloseConnectionEnable()) //
            && pooledConnection.ownerThread != Thread.currentThread()//
        ) {
            LOG.warn("get/close not same thread");
        }

        final Connection physicalConnection = holder.conn;
				// TODO 不懂 后续分析
        if (pooledConnection.traceEnable) {
            Object oldInfo = null;
            activeConnectionLock.lock();
            try {
                if (pooledConnection.traceEnable) {
                    oldInfo = activeConnections.remove(pooledConnection);
                    pooledConnection.traceEnable = false;
                }
            } finally {
                activeConnectionLock.unlock();
            }
            if (oldInfo == null) {
                if (LOG.isWarnEnabled()) {
                    LOG.warn("remove abandonded failed. activeConnections.size " + activeConnections.size());
                }
            }
        }
				// 获取该连接的配置
        final boolean isAutoCommit = holder.underlyingAutoCommit;
        final boolean isReadOnly = holder.underlyingReadOnly;
        final boolean testOnReturn = this.testOnReturn;

        try {
            // check need to rollback?
          	// 当该连接不是自动提交但又不是只读连接的时候（简单理解为手动控制事务），需要回滚该连接
            if ((!isAutoCommit) && (!isReadOnly)) {
              // 回滚的操作看下Connection接口的定义
                pooledConnection.rollback();
            }

            // 基于多线程安全考虑，不是本线程时加锁，但最终都会实行reset方法
            boolean isSameThread = pooledConnection.ownerThread == Thread.currentThread();
            if (!isSameThread) {
                final ReentrantLock lock = pooledConnection.lock;
                lock.lock();
                try {
                    holder.reset();
                } finally {
                    lock.unlock();
                }
            } else {
              // 简单来说，重置参数为默认值，管理所有的listener，关闭所有的statement，清空所有的警告
                holder.reset();
            }

            if (holder.discard) {
                return;
            }

            if (phyMaxUseCount > 0 && holder.useCount >= phyMaxUseCount) {
                discardConnection(holder);
                return;
            }

            if (physicalConnection.isClosed()) {
                lock.lock();
                try {
                    if (holder.active) {
                        activeCount--;
                        holder.active = false;
                    }
                    closeCount++;
                } finally {
                    lock.unlock();
                }
                return;
            }
          
            if (testOnReturn) {
                boolean validate = testConnectionInternal(holder, physicalConnection);
                if (!validate) {
                    JdbcUtils.close(physicalConnection);

                    destroyCountUpdater.incrementAndGet(this);

                    lock.lock();
                    try {
                        if (holder.active) {
                            activeCount--;
                            holder.active = false;
                        }
                        closeCount++;
                    } finally {
                        lock.unlock();
                    }
                    return;
                }
            }
            if (holder.initSchema != null) {
                holder.conn.setSchema(holder.initSchema);
                holder.initSchema = null;
            }

            if (!enable) {
                discardConnection(holder);
                return;
            }

            boolean result;
            final long currentTimeMillis = System.currentTimeMillis();

            if (phyTimeoutMillis > 0) {
                long phyConnectTimeMillis = currentTimeMillis - holder.connectTimeMillis;
                if (phyConnectTimeMillis > phyTimeoutMillis) {
                    discardConnection(holder);
                    return;
                }
            }

            lock.lock();
         // 到这里之前的操作都死判断要不要丢弃该连接，有通过连接数判断的，有通过连接重试的
            try {
                if (holder.active) {
                    activeCount--;
                    holder.active = false;
                }
                closeCount++;
							// 这里才是真正的连接归还，并且放在了DataSource connections数组的末尾
                result = putLast(holder, currentTimeMillis);
                recycleCount++;
            } finally {
                lock.unlock();
            }

            if (!result) {
                JdbcUtils.close(holder.conn);
                LOG.info("connection recyle failed.");
            }
        } catch (Throwable e) {
            holder.clearStatementCache();

            if (!holder.discard) {
                discardConnection(holder);
                holder.discard = true;
            }

            LOG.error("recyle error", e);
            recycleErrorCountUpdater.incrementAndGet(this);
        }
    }
```

