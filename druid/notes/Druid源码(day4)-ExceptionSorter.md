# Druid的ExceptionSorter

[Druid连接池介绍 · alibaba/druid Wiki (github.com)](https://github.com/alibaba/druid/wiki/Druid连接池介绍)

> 在Druid连接池的介绍中有这么一段话：
>
> Druid连接池内置经过长期反馈验证过的[ExceptionSorter](https://github.com/alibaba/druid/wiki/ExceptionSorter_cn) 。[ExceptionSorter](https://github.com/alibaba/druid/wiki/ExceptionSorter_cn) 的作用是：在数据库服务器重启、网络抖动、连接被服务器关闭等异常情况下，连接发生了不可恢复异常，将连接从连接池中移除，保证连接池在异常发生时情况下正常工作。**ExceptionSorter是连接池稳定的关键特性，没有[ExceptionSorter](https://github.com/alibaba/druid/wiki/ExceptionSorter_cn) 的连接池，不能认为是有稳定性保障的连接池。**

**问题：那么什么是ExceptionSorter呢**

Exception能理解异常，那Sorter是什么意思呢？有道给出的翻译是：分选机，异常分选机？

> 在连接池的介绍中也说明了，[ExceptionSorter](https://github.com/alibaba/druid/wiki/ExceptionSorter_cn) 的作用是：在数据库服务器重启、网络抖动、连接被服务器关闭等异常情况下，连接发生了不可恢复异常，将连接从连接池中移除，保证连接池在异常发生时情况下正常工作。

那么，它是如何工作的呢？

[ExceptionSorter_cn · alibaba/druid Wiki (github.com)](https://github.com/alibaba/druid/wiki/ExceptionSorter_cn)

**在Druid和JBoss连接池中，剔除“不可用连接”的机制称为ExceptionSorter，实现的原理是根据异常类型/Code/Reason/Message来识别“不可用连接”。**

### 源码解析-com.alibaba.druid.pool.vendor.MySqlExceptionSorter

```java
public class MySqlExceptionSorter implements ExceptionSorter {

    @Override
    public boolean isExceptionFatal(SQLException e) {
        if (e instanceof SQLRecoverableException) {
            return true;
        }

        final String sqlState = e.getSQLState();
        final int errorCode = e.getErrorCode();

        if (sqlState != null && sqlState.startsWith("08")) {
            return true;
        }
        
        switch (errorCode) {
            // Communications Errors
            case 1040: // ER_CON_COUNT_ERROR
            case 1042: // ER_BAD_HOST_ERROR
            case 1043: // ER_HANDSHAKE_ERROR
            case 1047: // ER_UNKNOWN_COM_ERROR
            case 1081: // ER_IPSOCK_ERROR
            case 1129: // ER_HOST_IS_BLOCKED
            case 1130: // ER_HOST_NOT_PRIVILEGED
            // Authentication Errors
            case 1045: // ER_ACCESS_DENIED_ERROR
            // Resource errors
            case 1004: // ER_CANT_CREATE_FILE
            case 1005: // ER_CANT_CREATE_TABLE
            case 1015: // ER_CANT_LOCK
            case 1021: // ER_DISK_FULL
            case 1041: // ER_OUT_OF_RESOURCES
            // Out-of-memory errors
            case 1037: // ER_OUTOFMEMORY
            case 1038: // ER_OUT_OF_SORTMEMORY
            // Access denied
            case 1142: // ER_TABLEACCESS_DENIED_ERROR
            case 1227: // ER_SPECIFIC_ACCESS_DENIED_ERROR

            case 1023: // ER_ERROR_ON_CLOSE

            case 1290: // ER_OPTION_PREVENTS_STATEMENT
                return true;
            default:
                break;
        }
        
        // for oceanbase
        if (errorCode >= -9000 && errorCode <= -8000) {
            return true;
        }
        
        String className = e.getClass().getName();
        if (className.endsWith(".CommunicationsException")) {
            return true;
        }

        String message = e.getMessage();
        if (message != null && message.length() > 0) {
            if (message.startsWith("Streaming result set com.mysql.jdbc.RowDataDynamic")
                    && message.endsWith("is still active. No statements may be issued when any streaming result sets are open and in use on a given connection. Ensure that you have called .close() on any active streaming result sets before attempting more queries.")) {
                return true;
            }
            
            final String errorText = message.toUpperCase();

            if ((errorCode == 0 && (errorText.contains("COMMUNICATIONS LINK FAILURE")) //
                    || errorText.contains("COULD NOT CREATE CONNECTION")) //
                    || errorText.contains("NO DATASOURCE") //
                    || errorText.contains("NO ALIVE DATASOURCE")) {
                return true;
            }
        }

        Throwable cause = e.getCause();
        for (int i = 0; i < 5 && cause != null; ++i) {
            if (cause instanceof SocketTimeoutException) {
                return true;
            }

            className = cause.getClass().getName();
            if (className.endsWith(".CommunicationsException")) {
                return true;
            }

            cause = cause.getCause();
        }
        
        return false;
    }

    @Override
    public void configFromProperties(Properties properties) {

    }

}
```

这段代码确实没什么费解的地方，看看调用处。

![image-20220514005429565](/../../../images/image-20220514005429565.png)

除了第一个不是Test case，其余都是。

```java
// exceptionSorter.isExceptionFatal
if (exceptionSorter != null && exceptionSorter.isExceptionFatal(sqlEx)) {
  handleFatalError(pooledConnection, sqlEx, sql);
}
```



![image-20220514005617166](/../../../images/image-20220514005617166.png)

最后找了几个执行出看，都是直接处理，然后抛出。