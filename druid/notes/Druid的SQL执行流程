# Druid的SQL执行流程

> com.alibaba.druid.pool.DruidPooledPreparedStatement#executeQuery

```java
public ResultSet executeQuery() throws SQLException {
    //检查连接，是否关闭，是否有异常
    checkOpen();
		//执行查询的次数++（增加的是DataSource的属性）
    incrementExecuteQueryCount()·
   //sql事务记录（不是自动提交的话，初始化事务管理对象：com.alibaba.druid.proxy.jdbc.TransactionInfo）
    transactionRecord(sql);
   	//oracle设置行预取
    oracleSetRowPrefetch();
		//更改SQL实行状态为running
    conn.beforeExecute();
    try {
      	// 实际执行executeQuery的类，debug发现为：PreparedStatementProxyImpl
        ResultSet rs = stmt.executeQuery();
        if (rs == null) {
            return null;
        }
				//连接池返回结果封装
        DruidPooledResultSet poolableResultSet = new DruidPooledResultSet(this, rs);
        //添加结果集跟踪 用于监控
        addResultSetTrace(poolableResultSet);

        return poolableResultSet;
    } catch (Throwable t) {
        errorCheck(t);

        throw checkException(t);
    } finally {
        //更新连接的running状态
        conn.afterExecute();
    }	
}
```

> com.alibaba.druid.proxy.jdbc.PreparedStatementProxyImpl#executeQuery

```java
public ResultSet executeQuery() throws SQLException {
  firstResultSet = true;

  updateCount = null;
  lastExecuteSql = sql;
  lastExecuteType = StatementExecuteType.ExecuteQuery;
  lastExecuteStartNano = -1L;
  lastExecuteTimeNano = -1L;
	// 调用com.alibaba.druid.filter.FilterChainImpl#preparedStatement_executeQuery
  return createChain().preparedStatement_executeQuery(this);
}
```

> com.alibaba.druid.filter.FilterChainImpl#preparedStatement_executeQuery

```java
 public ResultSetProxy preparedStatement_executeQuery(PreparedStatementProxy statement) throws SQLException {
   if (this.pos < filterSize) {
     // 执行过滤器的方法 SQL监控的过滤器类（FilterEventAdapter）
     return nextFilter().preparedStatement_executeQuery(this, statement);
   }
	 // 当前位置>过滤器的大小，贼用原始对象(不同数据的实现的关于JDBC的Driver)的执行SQL
   ResultSet resultSet = statement.getRawObject().executeQuery();
   if (resultSet == null) {
     return null;
   }
   // 如果结果集不为空，则做一层结果集的包装
   return new ResultSetProxyImpl(statement, resultSet, dataSource.createResultSetId(),
                                 statement.getLastExecuteSql());
 }
```

> SQL监控的过滤器类（FilterEventAdapter），保存SQL执行中的监控数据。说明了druid监控数据的来源。

```java
public ResultSetProxy preparedStatement_executeQuery(FilterChain chain, PreparedStatementProxy statement) throws SQLException {
    try {
        //sql实际执行之前 调用的是 如果子类是Log Filter的时候：组装sql执行的日志  如果是Stat Filter则记录对应的监控参数
        statementExecuteQueryBefore(statement, statement.getSql());
        ResultSetProxy resultSet = chain.preparedStatement_executeQuery(statement);
        if (resultSet != null) {
            //子类中Log Filter的方法组装sql执行的日志 or Stat Filter则记录对应的监控参数
            statementExecuteQueryAfter(statement, statement.getSql(), resultSet);
            //子类中Log Filter的方法组装sql执行的日志 or Stat Filter则记录对应的监控参数
            resultSetOpenAfter(resultSet);
        }
        return resultSet;
    } catch (SQLException error) {
        statement_executeErrorAfter(statement, statement.getSql(), error);
        throw error;
    } catch (RuntimeException error) {
        statement_executeErrorAfter(statement, statement.getSql(), error);
        throw error;
    } catch (Error error) {
        statement_executeErrorAfter(statement, statement.getSql(), error);
        throw error;
    }
}
```
