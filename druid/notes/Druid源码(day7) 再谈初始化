# Druid 再谈初始化

从测试用例出发

```java
public class MySql_Connect_test extends DbTestCase {
    public MySql_Connect_test() {
      // 父类在读取该地址的配置文件，做setUp，会初始化DataSource的配置
        super("pool_config/mysql_tddl.properties");
    }

    public void test_oracle_2() throws Exception {
        for (int i = 0; i < 10; ++i) {
          	// 1.获取连接
            Connection conn = getConnection();
						// 2.获取Statement对象
            Statement stmt = conn.createStatement();
            int updateCnt = stmt.executeUpdate("update tb1 set fid = '3' where fid = '4'");
            System.out.println("update : " + updateCnt);

            System.out.println(
                    MySqlUtils.getLastPacketReceivedTimeMs(conn));


            stmt.close();
//        rs.close();

            conn.close();
        }
    }
}
```

**1.获取连接**

> com.alibaba.druid.pool.DruidDataSource#getConnection()

![image-20220518171332808](/Users/yinshi/Library/Application Support/typora-user-images/image-20220518171332808.png)

可以看到，这里有2个getConnection方法，区别在于一个带参，一个无参，以及一个是实现父类，一个是Druid自己扩展。

**maxWait**这个参数的作用：获取连接的等待时长，超时抛异常。

继续向下执行com.alibaba.druid.pool.DruidDataSource#getConnection(long)会进入

> com.alibaba.druid.pool.DruidDataSource#init

![image-20220518171840417](/Users/yinshi/Library/Application Support/typora-user-images/image-20220518171840417.png)

```java
public void init() throws SQLException {
  			// 如图，inited被volatile修饰，默认为false，所以这里不会直接return
        if (inited) {
            return;
        }

        // bug fixed for dead lock, for issue #2980
        DruidDriver.getInstance();
				// 出于多线程考虑，加锁
        final ReentrantLock lock = this.lock;
        try {
					//如果当前线程已经持有这个锁，那么持有计数加一并且方法立即返回
          //如果锁被另一个线程持有，那么当前线程出于线程调度的目的而被禁用并处于休眠状态
            lock.lockInterruptibly();
        } catch (InterruptedException e) {
            throw new SQLException("interrupt", e);
        }

        boolean init = false;
        try {
          // 因为可能线程在获取锁的时候wait过，这里再次check inited的状态
            if (inited) {
                return;
            }
						// 这是String，初始化堆栈跟踪
            initStackTrace = Utils.toString(Thread.currentThread().getStackTrace());
           // 获取一个DataSource id，DruidDriver通过AtomicInteger保证原子性，且初始化值为0；
            this.id = DruidDriver.createDataSourceId();
            if (this.id > 1) {
              // 当有多个数据源时，就会执行这段逻辑，暂时不知道是做什么用
              // Q1:多数据源时，Druid的各种种子更新器的作用是什么?
                long delta = (this.id - 1) * 100000;
                this.connectionIdSeedUpdater.addAndGet(this, delta);
                this.statementIdSeedUpdater.addAndGet(this, delta);
                this.resultSetIdSeedUpdater.addAndGet(this, delta);
                this.transactionIdSeedUpdater.addAndGet(this, delta);
            }
						// URL以"jdbc:wrap-jdbc:"开头会初始化一段配置
            if (this.jdbcUrl != null) {
                this.jdbcUrl = this.jdbcUrl.trim();
                initFromWrapDriverUrl();
            }
						
            for (Filter filter : filters) {
                filter.init(this);
            }
						// 根据URL区分数据库类型
            if (this.dbTypeName == null || this.dbTypeName.length() == 0) {
                this.dbTypeName = JdbcUtils.getDbType(jdbcUrl, null);
            }
						
            DbType dbType = DbType.of(this.dbTypeName);
            if (JdbcUtils.isMysqlDbType(dbType)) {
              // 如果是MySQL 默认关闭cache，但是在要打开也就按需所取
                boolean cacheServerConfigurationSet = false;
                if (this.connectProperties.containsKey("cacheServerConfiguration")) {
                    cacheServerConfigurationSet = true;
                } else if (this.jdbcUrl.indexOf("cacheServerConfiguration") != -1) {
                    cacheServerConfigurationSet = true;
                }
                if (cacheServerConfigurationSet) {
                    this.connectProperties.put("cacheServerConfiguration", "true");
                }
            }
						// 最大存活数小于0
            if (maxActive <= 0) {
                throw new IllegalArgumentException("illegal maxActive " + maxActive);
            }
						// 最大存活数小于最小空闲数
            if (maxActive < minIdle) {
                throw new IllegalArgumentException("illegal maxActive " + maxActive);
            }
						// 初始化数量大于最打存活数
            if (getInitialSize() > maxActive) {
                throw new IllegalArgumentException("illegal initialSize " + this.initialSize + ", maxActive " + maxActive);
            }

            if (timeBetweenLogStatsMillis > 0 && useGlobalDataSourceStat) {
                throw new IllegalArgumentException("timeBetweenLogStatsMillis not support useGlobalDataSourceStat=true");
            }

            if (maxEvictableIdleTimeMillis < minEvictableIdleTimeMillis) {
                throw new SQLException("maxEvictableIdleTimeMillis must be grater than minEvictableIdleTimeMillis");
            }

            if (keepAlive && keepAliveBetweenTimeMillis <= timeBetweenEvictionRunsMillis) {
                throw new SQLException("keepAliveBetweenTimeMillis must be grater than timeBetweenEvictionRunsMillis");
            }

            if (this.driverClass != null) {
                this.driverClass = driverClass.trim();
            }
						// 通过SPI的方式加载扩展的filter类(扩展的filter必须标注@AutoLoad注解)
            initFromSPIServiceLoader();
						// 确定数据库驱动类型
            resolveDriver();
						// 做最后的初始化检查，分别对Oracle、MySQL、DB2做不同的检查
            initCheck();
						// 初始化异常包装器，用于对接不同数据库的code，做统一异常转换
            initExceptionSorter();
            // 初始化有效连接检查器
            initValidConnectionChecker();
            // 验证查询检查
            validationQueryCheck();
						// 初始化用于监控的
            if (isUseGlobalDataSourceStat()) {
                dataSourceStat = JdbcDataSourceStat.getGlobal();
                if (dataSourceStat == null) {
                    dataSourceStat = new JdbcDataSourceStat("Global", "Global", this.dbTypeName);
                    JdbcDataSourceStat.setGlobal(dataSourceStat);
                }
                if (dataSourceStat.getDbType() == null) {
                    dataSourceStat.setDbType(this.dbTypeName);
                }
            } else {
                dataSourceStat = new JdbcDataSourceStat(this.name, this.jdbcUrl, this.dbTypeName, this.connectProperties);
            }
            dataSourceStat.setResetStatEnable(this.resetStatEnable);
						// 初始化三个连接池
            connections = new DruidConnectionHolder[maxActive];
            evictConnections = new DruidConnectionHolder[maxActive];
            keepAliveConnections = new DruidConnectionHolder[maxActive];

            SQLException connectError = null;
						// 连接池是否要异步创建
            if (createScheduler != null && asyncInit) {
                for (int i = 0; i < initialSize; ++i) {
                    submitCreateTask(true);
                }
            } else if (!asyncInit) {
                // init connections
                while (poolingCount < initialSize) {
                    try {
                      // 按条件初始化一定数量的连接，放入connections去
                        PhysicalConnectionInfo pyConnectInfo = createPhysicalConnection();
                        DruidConnectionHolder holder = new DruidConnectionHolder(this, pyConnectInfo);
                        connections[poolingCount++] = holder;
                    } catch (SQLException ex) {
                        LOG.error("init datasource error, url: " + this.getUrl(), ex);
                        if (initExceptionThrow) {
                            connectError = ex;
                            break;
                        } else {
                            Thread.sleep(3000);
                        }
                    }
                }

                if (poolingCount > 0) {
                    poolingPeak = poolingCount;
                    poolingPeakTime = System.currentTimeMillis();
                }
            }
						// 创建了三个线程，通过initedLatch保证线程创建完成
            createAndLogThread();
            createAndStartCreatorThread();
            createAndStartDestroyThread();

            initedLatch.await();
            init = true;

            initedTime = new Date();
            registerMbean();

            if (connectError != null && poolingCount == 0) {
                throw connectError;
            }

            if (keepAlive) {
                // async fill to minIdle
                if (createScheduler != null) {
                    for (int i = 0; i < minIdle; ++i) {
                        submitCreateTask(true);
                    }
                } else {
                    this.emptySignal();
                }
            }

        } catch (SQLException e) {
            LOG.error("{dataSource-" + this.getID() + "} init error", e);
            throw e;
        } catch (InterruptedException e) {
            throw new SQLException(e.getMessage(), e);
        } catch (RuntimeException e){
            LOG.error("{dataSource-" + this.getID() + "} init error", e);
            throw e;
        } catch (Error e){
            LOG.error("{dataSource-" + this.getID() + "} init error", e);
            throw e;

        } finally {
          // 最后修改inited的值，可是如果有异常咋办？
            inited = true;
            lock.unlock();

            if (init && LOG.isInfoEnabled()) {
                String msg = "{dataSource-" + this.getID();

                if (this.name != null && !this.name.isEmpty()) {
                    msg += ",";
                    msg += this.name;
                }

                msg += "} inited";

                LOG.info(msg);
            }
        }
    }

```



问题清单

Q1:多数据源时，Druid的各种种子更新器的作用是什么?

Q2:在init方法的finally块修改inited的值，可是如果有异常咋办？
