package com.like.code.core.database.interceptor;

import com.baomidou.mybatisplus.core.toolkit.ClassUtils;
import com.baomidou.mybatisplus.core.toolkit.StringPool;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.toolkit.PropertyMapper;
import com.like.code.core.util.SqlParserUtil;
import lombok.Setter;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.*;

/**
 * @author: like
 * @since: 2021/5/18 21:59
 * @email: 980650920@qq.com
 * @desc: copy MyBatisSqlInterceptor
 */
@Intercepts(
        {
                @Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}),
                @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
                @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
                @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class, CacheKey.class, BoundSql.class}),
        }
)
public class MyBatisSqlLogInterceptor extends MybatisPlusInterceptor {
    private final static Logger log = LoggerFactory.getLogger(MyBatisSqlLogInterceptor.class);

    @Setter
    private List<InnerInterceptor> interceptors = new ArrayList<>();

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object target = invocation.getTarget();
        Object[] args = invocation.getArgs();
        if (target instanceof Executor) {
            final Executor executor = (Executor) target;
            Object parameter = args[1];
            boolean isUpdate = args.length == 2;
            MappedStatement ms = (MappedStatement) args[0];
            if (!isUpdate && ms.getSqlCommandType() == SqlCommandType.SELECT) {
                RowBounds rowBounds = (RowBounds) args[2];
                ResultHandler resultHandler = (ResultHandler) args[3];
                BoundSql boundSql;
                if (args.length == 4) {
                    boundSql = ms.getBoundSql(parameter);
                } else {
                    // 几乎不可能走进这里面,除非使用Executor的代理对象调用query[args[6]]
                    boundSql = (BoundSql) args[5];
                }
                for (InnerInterceptor query : interceptors) {
                    if (!query.willDoQuery(executor, ms, parameter, rowBounds, resultHandler, boundSql)) {
                        return Collections.emptyList();
                    }
                    query.beforeQuery(executor, ms, parameter, rowBounds, resultHandler, boundSql);
                }

                // sql 日志打印
                log.info("\r");
                log.info(String.format("【Execute Sql】%s", SqlParserUtil.handleSql(boundSql.getSql(), ms, boundSql)));

                CacheKey cacheKey = executor.createCacheKey(ms, parameter, rowBounds, boundSql);
                return executor.query(ms, parameter, rowBounds, resultHandler, cacheKey, boundSql);
            } else if (isUpdate) {
                for (InnerInterceptor update : interceptors) {
                    if (!update.willDoUpdate(executor, ms, parameter)) {
                        return -1;
                    }
                    update.beforeUpdate(executor, ms, parameter);
                }
            }
        } else {
            // StatementHandler
            final StatementHandler sh = (StatementHandler) target;
            Connection connections = (Connection) args[0];
            Integer transactionTimeout = (Integer) args[1];
            for (InnerInterceptor innerInterceptor : interceptors) {
                innerInterceptor.beforePrepare(sh, connections, transactionTimeout);
            }
        }
        return invocation.proceed();
    }

    @Override
    public Object plugin(Object target) {
        if (target instanceof Executor || target instanceof StatementHandler) {
            return Plugin.wrap(target, this);
        }
        return target;
    }

    public void addInnerInterceptor(InnerInterceptor innerInterceptor) {
        this.interceptors.add(innerInterceptor);
    }

    public List<InnerInterceptor> getInterceptors() {
        return Collections.unmodifiableList(interceptors);
    }

    /**
     * 使用内部规则,拿分页插件举个栗子:
     * <p>
     * - key: "@page" ,value: "com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor"
     * - key: "page:limit" ,value: "100"
     * <p>
     * 解读1: key 以 "@" 开头定义了这是一个需要组装的 `InnerInterceptor`, 以 "page" 结尾表示别名
     * value 是 `InnerInterceptor` 的具体的 class 全名
     * 解读2: key 以上面定义的 "别名 + ':'" 开头指这个 `value` 是定义的该 `InnerInterceptor` 属性需要设置的值
     * <p>
     * 如果这个 `InnerInterceptor` 不需要配置属性也要加别名
     */
    @Override
    public void setProperties(Properties properties) {
        PropertyMapper pm = PropertyMapper.newInstance(properties);
        Map<String, Properties> group = pm.group(StringPool.AT);
        group.forEach((k, v) -> {
            InnerInterceptor innerInterceptor = ClassUtils.newInstance(k);
            innerInterceptor.setProperties(v);
            addInnerInterceptor(innerInterceptor);
        });
    }
}
