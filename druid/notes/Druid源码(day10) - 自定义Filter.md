# Druid源码(day10) - 自定义Filter

上篇笔记研究了Filter的过滤原理，今天来自定义一个Filter

> 参考StatFilter

```java
public class StatFilter extends FilterEventAdapter implements StatFilterMBean {}
```



配合文件

```properties
#-------------基本属性--------------------------------
druid.url=url
druid.username=root
druid.password=root
#数据源名，当配置多数据源时可以用于区分。注意，1.0.5版本及更早版本不支持配置该项
#默认"DataSource-" + System.identityHashCode(this)
druid.name=100898
#如果不配置druid会根据url自动识别dbType，然后选择相应的driverClassName
druid.driverClassName=com.mysql.cj.jdbc.Driver

#-------------连接池大小相关参数--------------------------------
#初始化时建立物理连接的个数
#默认为0
druid.initialSize=4

#最大连接池数量
#默认为8
druid.maxActive=8

#最小空闲连接数量
#默认为0
druid.minIdle=0

#已过期
#maxIdle

#获取连接时最大等待时间，单位毫秒。
#配置了maxWait之后，缺省启用公平锁，并发效率会有所下降，如果需要可以通过配置useUnfairLock属性为true使用非公平锁。
#默认-1，表示无限等待
druid.maxWait=-1

druid.filters=stat,cus

```



```java
public class CustomizeFilter extends FilterEventAdapter {

    @Override
    protected void statementExecuteUpdateBefore(StatementProxy statement, String sql) {
        System.out.println("statementExecuteUpdateBefore");
        super.statementExecuteUpdateBefore(statement, sql);
    }

    @Override
    protected void statementExecuteUpdateAfter(StatementProxy statement, String sql, int updateCount) {
        System.out.println("statementExecuteUpdateAfter");
        super.statementExecuteUpdateAfter(statement, sql, updateCount);
    }
}
```

在更新语句的前后分别在控制台进行了输出

```java
public class FilterTest {


    public static void main(String[] args) throws Exception {
        DruidDataSource dataSource = createDataSourceFromResource("mysql_tddl.properties");

        List<String> filterClassNames = dataSource.getFilterClassNames();

        System.out.println(filterClassNames);
        DruidPooledConnection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        String sql = "UPDATE `record` SET `time` = CURRENT_TIME WHERE `id` = 1;";
        statement.executeUpdate(sql);
        // close
    }
}

```

执行获取连接，执行SQL的操作，查看控制台，然后看到控制台没有任何输出。然后想Druid默认的一些Filter都是通过配置文件别名的形式来引入，那么Druid是如何做的呢。再看Filter的加载过程

```java
public static void loadFilter(List<Filter> filters, String filterName) throws SQLException {
  if (filterName.length() != 0) {
    String filterClassNames = getFilter(filterName);
    //省略若干代码
  }
}

public static final String getFilter(String alias) {
  if (alias == null) {
    return null;
  } else {
    String filter = (String)aliasMap.get(alias);
    if (filter == null && alias.length() < 128) {
      filter = alias;
    }

    return filter;
  }
}
```

可以看到别名到filterClassNames的映射是通过一个aliasMap取的，那么这个aliasMap是如何初始化的呢？

```java
public class FilterManager {

    private final static Log                               LOG      = LogFactory.getLog(FilterManager.class);

    private static final ConcurrentHashMap<String, String> aliasMap = new ConcurrentHashMap<String, String>(16, 0.75f, 1);

    static {
        try {
            Properties filterProperties = loadFilterConfig();
            for (Map.Entry<Object, Object> entry : filterProperties.entrySet()) {
                String key = (String) entry.getKey();
                if (key.startsWith("druid.filters.")) {
                    String name = key.substring("druid.filters.".length());
                    aliasMap.put(name, (String) entry.getValue());
                }
            }
        } catch (Throwable e) {
            LOG.error("load filter config error", e);
        }
    }
  
  public static Properties loadFilterConfig() throws IOException {
        Properties filterProperties = new Properties();

        loadFilterConfig(filterProperties, ClassLoader.getSystemClassLoader());
        loadFilterConfig(filterProperties, FilterManager.class.getClassLoader());
        loadFilterConfig(filterProperties, Thread.currentThread().getContextClassLoader());

        return filterProperties;
    }

    private static void loadFilterConfig(Properties filterProperties, ClassLoader classLoader) throws IOException {
        if (classLoader == null) {
            return;
        }
        
        for (Enumeration<URL> e = classLoader.getResources("META-INF/druid-filter.properties"); e.hasMoreElements();) {
            URL url = e.nextElement();

            Properties property = new Properties();

            InputStream is = null;
            try {
                is = url.openStream();
                property.load(is);
            } finally {
                JdbcUtils.close(is);
            }

            filterProperties.putAll(property);
        }
    }
  
}
```

通过static块代码，将META-INF/druid-filter.properties配置文件下的值put到aliasMap

![image-20220521001844897](https://cdn.superyins.cn/druid/image-20220521001844897.png)

相同操作之后，再试试

![image-20220521001930288](https://cdn.superyins.cn/druid/image-20220521001930288.png)

果然就有了，一个简单的Druid Filter就实现了