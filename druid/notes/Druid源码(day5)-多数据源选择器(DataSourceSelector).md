# Druid源码(day5)-多数据源选择器(DataSourceSelector)

## com.alibaba.druid.pool.ha.selector.DataSourceSelector

接口定义

```java
/**
 * Interface for those selector to implement.
 * e.g. Random and Named
 *
 * @author DigitalSonic
 */
public interface DataSourceSelector {
    /**
     * Return a DataSource according to the implemention.
     */
    DataSource get();

    /**
     * Set the target DataSource name to return.
     * Wether to use this or not, it's decided by the implemention.
     */
    void setTarget(String name);

    /**
     * Return the name of this DataSourceSelector.
     * e.g. byName
     */
    String getName();

    /**
     * Init the DataSourceSelector before use it.
     */
    void init();

    /**
     * Destroy the DataSourceSelector, maybe interrupt the Thread.
     */
    void destroy();
}
// implements class


/**
 * Use the given name in ThreadLocal variable to choose DataSource.
 * 使用 ThreadLocal 变量中的给定名称来选择 DataSource。
 * @author DigitalSonic
 */
public class NamedDataSourceSelector implements DataSourceSelector {}
/**
 * A selector which uses java.util.Random to choose DataSource.
 * 使用 java.util.Random 选择 DataSource 的选择器。
 *
 * @author DigitalSonic
 */
public class RandomDataSourceSelector implements DataSourceSelector {}
/**
 * An extend selector based on RandomDataSourceSelector which can stick a DataSource to a Thread in a while.
 * 一个基于 RandomDataSourceSelector 的扩展选择器，它可以在一段时间内将 DataSource 粘贴到线程。
 */
public class StickyRandomDataSourceSelector extends RandomDataSourceSelector {}
```

- NamedDataSourceSelector

```java
public class NamedDataSourceSelector implements DataSourceSelector {
    public static final String DEFAULT_NAME = "default";
  	// 真正存放数据源的地方
    private HighAvailableDataSource highAvailableDataSource;
    // 通过ThreadLocal来放DataSource的beanName;
    private ThreadLocal<String> targetDataSourceName = new ThreadLocal<String>();
    private String defaultName = DEFAULT_NAME;

    public NamedDataSourceSelector(HighAvailableDataSource highAvailableDataSource) {
        this.highAvailableDataSource = highAvailableDataSource;
    }

    @Override
    public void init() {
    }

    @Override
    public void destroy() {
    }

    @Override
    public String getName() {
        return DataSourceSelectorEnum.BY_NAME.getName();
    }

    @Override
    public DataSource get() {
        if (highAvailableDataSource == null) {
            return null;
        }

        Map<String, DataSource> dataSourceMap = highAvailableDataSource.getAvailableDataSourceMap();
        if (dataSourceMap == null || dataSourceMap.isEmpty()) {
            return null;
        }
        if (dataSourceMap.size() == 1) {
          //这样写和getDefault有什么不一样呢？
            for (DataSource v : dataSourceMap.values()) {
                return v;
            }
        }
        String name = getTarget();
        if (name == null) {
            if (dataSourceMap.get(getDefaultName()) != null) {
                return dataSourceMap.get(getDefaultName());
            }
        } else {
            return dataSourceMap.get(name);
        }
        return null;
    }

    @Override
  	// 设置target的名称
    public void setTarget(String name) {
        targetDataSourceName.set(name);
    }

    public String getTarget() {
        return targetDataSourceName.get();
    }

    public void resetDataSourceName() {
        targetDataSourceName.remove();
    }

    public String getDefaultName() {
        return defaultName;
    }

    public void setDefaultName(String defaultName) {
        this.defaultName = defaultName;
    }
}
```

> NamedDataSourceSelector的实现来说相对简单，通过构造参数注入一个HighAvailableDataSource，有个get方法，然后是设置dataSource BeanName的方法.

**这种Selector适用的场景是什么呢？**

*适用场景：主从数据源。通过提前设置数据源名称（高可用数据源设置方法），做到数据源的切换。或者固定的读写分离数据源，或者指定不同的业务适用不同的数据源*

- RandomDataSourceSelector

```java
@Override
public DataSource get() {
  Map<String, DataSource> dataSourceMap = getDataSourceMap();
  if (dataSourceMap == null || dataSourceMap.isEmpty()) {
    return null;
  }
	// 移除黑名单的DataSource 什么意思呢？
  Collection<DataSource> targetDataSourceSet = removeBlackList(dataSourceMap);
  removeBusyDataSource(targetDataSourceSet);
  DataSource dataSource = getRandomDataSource(targetDataSourceSet);
  return dataSource;
}
```

> 与NamedDataSourceSelector#get()不同的点在于获取之前有2个操作，分别是移除黑名单和过滤繁忙的数据源

```java
public void init() {
  if (highAvailableDataSource == null) {
    LOG.warn("highAvailableDataSource is NULL!");
    return;
  }
  if (!highAvailableDataSource.isTestOnBorrow() && !highAvailableDataSource.isTestOnReturn()) {
    loadProperties();
    initThreads();
  } else {
    LOG.info("testOnBorrow or testOnReturn has been set to true, ignore validateThread");
  }
}
```

> NamedDataSourceSelector的init方法为空实现，而RandomDataSourceSelector有具体实现，分别是加载配置和初始化线程

**加载了什么配置**

```java
private void loadProperties() {
    // 检查间隔秒数
    checkingIntervalSeconds = loadInteger(PROP_CHECKING_INTERVAL, checkingIntervalSeconds);
  	// 恢复间隔秒数
    recoveryIntervalSeconds = loadInteger(PROP_RECOVERY_INTERVAL, recoveryIntervalSeconds);
    // 验证睡眠秒数
    validationSleepSeconds = loadInteger(PROP_VALIDATION_SLEEP, validationSleepSeconds);
  	// 黑名单阈值
    blacklistThreshold = loadInteger(PROP_BLACKLIST_THRESHOLD, blacklistThreshold);
}
```

初始化了跟线程工作相关的配置

**初始化的线程是用来干什么的**

```java
private void initThreads() {
    if (validateThread == null) {
        validateThread = new RandomDataSourceValidateThread(this);
        validateThread.setCheckingIntervalSeconds(checkingIntervalSeconds);
        validateThread.setValidationSleepSeconds(validationSleepSeconds);
        validateThread.setBlacklistThreshold(blacklistThreshold);
    } else {
        validateThread.setSelector(this);
    }
    if (runningValidateThread != null) {
        runningValidateThread.interrupt();
    }
    runningValidateThread = new Thread(validateThread, "RandomDataSourceSelector-validate-thread");
    runningValidateThread.start();

    if (recoverThread == null) {
        recoverThread = new RandomDataSourceRecoverThread(this);
        recoverThread.setRecoverIntervalSeconds(recoveryIntervalSeconds);
        recoverThread.setValidationSleepSeconds(validationSleepSeconds);
    } else {
        recoverThread.setSelector(this);
    }
    if (runningRecoverThread != null) {
        runningRecoverThread.interrupt();
    }
    runningRecoverThread = new Thread(recoverThread, "RandomDataSourceSelector-recover-thread");
    runningRecoverThread.start();
}
```

可以看到这段代码初始化了2个线程，一个用户DataSource的校验，一个用户DataSource的恢复

然后get的工作就比较简单了

```java
private DataSource getRandomDataSource(Collection<DataSource> dataSourceSet) {
    DataSource[] dataSources = dataSourceSet.toArray(new DataSource[] {});
    if (dataSources != null && dataSources.length > 0) {
        return dataSources[random.nextInt(dataSourceSet.size())];
    }
    return null;
}
```

在满足条件的数据源中随机选择一个