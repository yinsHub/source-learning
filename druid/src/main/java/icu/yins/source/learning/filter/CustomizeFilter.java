package icu.yins.source.learning.filter;

import com.alibaba.druid.filter.AutoLoad;
import com.alibaba.druid.filter.FilterEventAdapter;
import com.alibaba.druid.proxy.jdbc.StatementProxy;

/**
 * 自定义Druid过滤器
 *
 * @author <a href="mailto:yins_emial@foxmail.com">yins</a>
 * @date 2022-05-20 23:43
 */
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
