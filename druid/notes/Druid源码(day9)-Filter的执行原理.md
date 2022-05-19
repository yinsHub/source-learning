# Druid源码(day9)-Filter的执行原理

## 1.Filter的初始化

在讲Filter的执行原理之前，不得不Filter的初始化

在com.alibaba.druid.pool.DruidDataSource#configFromPropety有这么一段代码

```java
{
  String property = properties.getProperty("druid.filters");

  if (property != null && property.length() > 0) {
    try {
      this.setFilters(property);
    } catch (SQLException e) {
      LOG.error("setFilters error", e);
    }
  }
}
```

```java
public void setFilters(String filters) throws SQLException {
  if (filters != null && filters.startsWith("!")) {
    filters = filters.substring(1);
    this.clearFilters();
  }
  this.addFilters(filters);
}
```

```java
public void addFilters(String filters) throws SQLException {
  if (filters == null || filters.length() == 0) {
    return;
  }

  String[] filterArray = filters.split("\\,");

  for (String item : filterArray) {
    FilterManager.loadFilter(this.filters, item.trim());
  }
}
```

当我们通过配置文件或者其他方式注入了过滤器时，就会在初始化配置文件，完成对Filter的初始化

**Druid内置的过滤器**

![image-20220519225934669](https://cdn.superyins.cn/druid/image-20220519225934669.png)

## 2.DruidDataSource的Filters

![image-20220519230424054](https://cdn.superyins.cn/druid/image-20220519230424054.png)

可以看到，Druid的DataSource是一个集合，同时我们拿到的conn和stmt都是Druid包装过后

## 3.Filter工作在--SQL执行时

> com.alibaba.druid.pool.DruidPooledStatement#executeUpdate(java.lang.String)

![image-20220519231201077](https://cdn.superyins.cn/druid/image-20220519231201077.png)

> com.alibaba.druid.proxy.jdbc.StatementProxyImpl#executeUpdate(java.lang.String)

```java
public int executeUpdate(String sql) throws SQLException {
  firstResultSet = false;
  lastExecuteSql = sql;
  lastExecuteType = StatementExecuteType.ExecuteUpdate;
  lastExecuteStartNano = -1L;
  lastExecuteTimeNano = -1L;
	// 创建一个过滤器实现类，并且通过构造注入了DataSource与初始化了FilterChainImpl的filterSize属性
  FilterChainImpl chain = createChain();
  updateCount = chain.statement_executeUpdate(this, sql);
  recycleFilterChain(chain);
  return updateCount;
}
```

> com.alibaba.druid.filter.FilterChainImpl#statement_executeUpdate(com.alibaba.druid.proxy.jdbc.StatementProxy, java.lang.String)

```java
public int statement_executeUpdate(StatementProxy statement, String sql) throws SQLException {
  // pos初始值为0，小于filterSize成立
  if (this.pos < filterSize) {
    // nextFilter取FilterChainImpl的DataSource里面的filters的pos++，先取后+1
    return nextFilter().statement_executeUpdate(this, statement, sql);
  }
  return statement.getRawObject().executeUpdate(sql);
}
```

然后执行对应DataSource#filters[pos]的statement_executeUpdate方法，以com.alibaba.druid.filter.stat.StatFilter举例

```java
public class StatFilter extends FilterEventAdapter implements StatFilterMBean{}
```

> com.alibaba.druid.filter.FilterEventAdapter#statement_executeUpdate(com.alibaba.druid.filter.FilterChain, com.alibaba.druid.proxy.jdbc.StatementProxy, java.lang.String)

```java
public int statement_executeUpdate(FilterChain chain, StatementProxy statement, String sql) throws SQLException {
  // SQL执行前
  statementExecuteUpdateBefore(statement, sql);

  try {
    // 执行super的statement_executeUpdate
    int updateCount = super.statement_executeUpdate(chain, statement, sql);
		// SQL执行后
    statementExecuteUpdateAfter(statement, sql, updateCount);

    return updateCount;
  } catch (SQLException error) {
    statement_executeErrorAfter(statement, sql, error);
    throw error;
  } catch (RuntimeException error) {
    statement_executeErrorAfter(statement, sql, error);
    throw error;
  } catch (Error error) {
    statement_executeErrorAfter(statement, sql, error);
    throw error;
  }
}
```

> com.alibaba.druid.filter.FilterAdapter#statement_executeUpdate(com.alibaba.druid.filter.FilterChain, com.alibaba.druid.proxy.jdbc.StatementProxy, java.lang.String)

```java
public int statement_executeUpdate(FilterChain chain, StatementProxy statement, String sql) throws SQLException {
  // 这里结合下面的代码一起看，因为这离又回到下面代码了，因为
  // public class FilterChainImpl implements FilterChain {}
  // 它本身也是一个FilterChain，只是现在pos不在是0了
  // FilterChain都会比较熟悉，责任链模式
  return chain.statement_executeUpdate(statement, sql);
}

// com.alibaba.druid.filter.FilterChainImpl#statement_executeUpdate(com.alibaba.druid.proxy.jdbc.StatementProxy, java.lang.String)
public int statement_executeUpdate(StatementProxy statement, String sql) throws SQLException {
  // pos初始值为0，小于filterSize成立
  if (this.pos < filterSize) {
    // nextFilter取FilterChainImpl的DataSource里面的filters的pos++，先取后+1
    return nextFilter().statement_executeUpdate(this, statement, sql);
  }
  return statement.getRawObject().executeUpdate(sql);
}
```

最后，简单来说就是通过stmt的包装对象，在执行SQL之前createChain并且注入DataSource本身的filter，然后通知一种责任链的方式，执行到每一个配置的filter，而Druid的Filter定义了FilterEventAdapter，在SQL执行前后分别留了对应的方法供我们调用。