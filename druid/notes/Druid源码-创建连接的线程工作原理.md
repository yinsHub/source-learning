## Druid 创建连接的线程工作原理

### 连接的基础

> 通过前2天的源码阅读，知道了Druid是通过DruidDataSource中的connections数组来维持连接的，数组的特点是声明后不可变（跟需要配置最大连接数相呼应）

```java
// store
private volatile DruidConnectionHolder[] connections;
```

> 今天继续分析连接的创建细节，在init方法中，有这样一段代码

```java
// 同时启动还有一个log的线程
createAndLogThread();
createAndStartCreatorThread();
createAndStartDestroyThread();
 
initedLatch.await();
```

> 这里一共有3个线程，1个日志线程（不重要），而另外2个从名字上也能知道，一个管创建的线程和一个管销毁的线程。
>
> 并且这里initedLanch.await保证这2个线程一定启动了（initedLatch初始化为2，每个线程启动后都会减一）

**com.alibaba.druid.pool.DruidDataSource.CreateConnectionThread**start开始后的工作流程

```java
 public class CreateConnectionThread extends Thread {

        public CreateConnectionThread(String name){
            super(name);
            this.setDaemon(true);
        }

        public void run() {
            // 启动后减一，等待销毁线程的也减一，才确保这两个线程都成功创建了
            initedLatch.countDown();

            long lastDiscardCount = 0;
            int errorCount = 0;
            for (;;) {
                // addLast
                try {
                    lock.lockInterruptibly();
                } catch (InterruptedException e2) {
                    break;
                }

                long discardCount = DruidDataSource.this.discardCount;
                boolean discardChanged = discardCount - lastDiscardCount > 0;
                lastDiscardCount = discardCount;

                try {
                	// 等待创建连接，默认true，表示要等待，不创建 
                    boolean emptyWait = true;
									// 如果连接池为空并且没有创建错误，才需要创建
                    if (createError != null
                            && poolingCount == 0
                            && !discardChanged) {
                        emptyWait = false;
                    }
									// 连接池为0 和异步创建会互斥
                    if (emptyWait
                            && asyncInit && createCount < initialSize) {
                        emptyWait = false;
                    }

                    if (emptyWait) {
                        // 必须存在线程等待，才创建连接
                        if (poolingCount >= notEmptyWaitThreadCount //
                                && (!(keepAlive && activeCount + poolingCount < minIdle))
                                && !isFailContinuous()
                        ) {
                            empty.await();
                        }

                        // 防止创建超过maxActive数量的连接
                        if (activeCount + poolingCount >= maxActive) {
                            empty.await();
                            continue;
                        }
                    }

                } catch (InterruptedException e) {
                    lastCreateError = e;
                    lastErrorTimeMillis = System.currentTimeMillis();

                    if ((!closing) && (!closed)) {
                        LOG.error("create connection Thread Interrupted, url: " + jdbcUrl, e);
                    }
                    break;
                } finally {
                    lock.unlock();
                }

                PhysicalConnectionInfo connection = null;

                try {
                    connection = createPhysicalConnection();
                } catch (SQLException e) {
                    LOG.error("create connection SQLException, url: " + jdbcUrl + ", errorCode " + e.getErrorCode()
                              + ", state " + e.getSQLState(), e);

                    errorCount++;
                    if (errorCount > connectionErrorRetryAttempts && timeBetweenConnectErrorMillis > 0) {
                        // fail over retry attempts
                        setFailContinuous(true);
                        if (failFast) {
                            lock.lock();
                            try {
                                notEmpty.signalAll();
                            } finally {
                                lock.unlock();
                            }
                        }

                        if (breakAfterAcquireFailure) {
                            break;
                        }

                        try {
                            Thread.sleep(timeBetweenConnectErrorMillis);
                        } catch (InterruptedException interruptEx) {
                            break;
                        }
                    }
                } catch (RuntimeException e) {
                    LOG.error("create connection RuntimeException", e);
                    setFailContinuous(true);
                    continue;
                } catch (Error e) {
                    LOG.error("create connection Error", e);
                    setFailContinuous(true);
                    break;
                }

                if (connection == null) {
                    continue;
                }
								// 将创建的连接放入连接池中
                boolean result = put(connection);
                if (!result) {
                    JdbcUtils.close(connection.getPhysicalConnection());
                    LOG.info("put physical connection to pool failed.");
                }

                errorCount = 0; // reset errorCount

                if (closing || closed) {
                    break;
                }
            }
        }
    }
```

> com.alibaba.druid.pool.DruidDataSource#put(com.alibaba.druid.pool.DruidAbstractDataSource.PhysicalConnectionInfo)

```java
protected boolean put(PhysicalConnectionInfo physicalConnectionInfo) {
        DruidConnectionHolder holder = null;
        try {
          // 初始化holder
            holder = new DruidConnectionHolder(DruidDataSource.this, physicalConnectionInfo);
        } catch (SQLException ex) {
            lock.lock();
            try {
                if (createScheduler != null) {
                    clearCreateTask(physicalConnectionInfo.createTaskId);
                }
            } finally {
                lock.unlock();
            }
            LOG.error("create connection holder error", ex);
            return false;
        }
			
        return put(holder, physicalConnectionInfo.createTaskId, false);
    }

private boolean put(DruidConnectionHolder holder, long createTaskId, boolean checkExists) {
        lock.lock();
        try {
            if (this.closing || this.closed) {
                return false;
            }
						// 连接池如果大于最大数量，那么清理这个创建任务
            if (poolingCount >= maxActive) {
                if (createScheduler != null) {
                    clearCreateTask(createTaskId);
                }
                return false;
            }

            if (checkExists) {
                for (int i = 0; i < poolingCount; i++) {
                    if (connections[i] == holder) {
                        return false;
                    }
                }
            }
						// 放入数组中
            connections[poolingCount] = holder;
          	// 连接数+1
            incrementPoolingCount();

            if (poolingCount > poolingPeak) {
                poolingPeak = poolingCount;
                poolingPeakTime = System.currentTimeMillis();
            }
						// 这个notEmpty是一个信号量，这个方法是通知的作用
            notEmpty.signal();
            notEmptySignalCount++;

            if (createScheduler != null) {
             // 如果创建线程不是null，那么清理创建队列的任务，因为这个方法从创建任务队列也可进入
                clearCreateTask(createTaskId);

                if (poolingCount + createTaskCount < notEmptyWaitThreadCount //
                    && activeCount + poolingCount + createTaskCount < maxActive) {
                    emptySignal();
                }
            }
        } finally {
            lock.unlock();
        }
        return true;
    }
```

