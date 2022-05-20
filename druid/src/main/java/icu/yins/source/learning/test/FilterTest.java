package icu.yins.source.learning.test;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidPooledConnection;
import com.alibaba.druid.util.JdbcUtils;
import com.alibaba.druid.util.MySqlUtils;
import jdk.jfr.DataAmount;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;

/**
 * TODO
 *
 * @author <a href="mailto:yins_emial@foxmail.com">yins</a>
 * @date 2022-05-20 23:46
 */
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

    static DruidDataSource createDataSourceFromResource(String resource) throws IOException {
        Properties properties = new Properties();

        InputStream configStream = null;
        try {
            configStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
            properties.load(configStream);
        } finally {
            JdbcUtils.close(configStream);
        }

        DruidDataSource dataSource = new DruidDataSource();
        dataSource.configFromPropety(properties);
        return dataSource;
    }
}
