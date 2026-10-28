package com.hookflow.endpoint.application; // 接收端查询用例属于应用层。

import java.util.List; // 表示一页接收端配置。
import java.util.Optional; // 表示按 ID 查询可能没有结果。
import org.springframework.context.annotation.Profile; // 排除无数据库的 skeleton 配置。
import org.springframework.stereotype.Service; // 注册接收端查询服务。
import com.hookflow.endpoint.domain.EndpointConfiguration; // 使用不含密钥的内部配置。
import com.hookflow.endpoint.infrastructure.EndpointRepository; // 调用接收端 JDBC 仓库。

@Profile("!skeleton") // 只有数据库配置启用时才创建查询服务。
@Service // 供后续管理员 Controller 注入。
public class EndpointQueryService { // 提供接收端详情和分页查询。
    private final EndpointRepository endpoints; // 保存接收端仓库依赖。

    public EndpointQueryService(EndpointRepository endpoints) { // 由 Spring 注入仓库。
        this.endpoints = endpoints; // 保存仓库供查询方法调用。
    } // 构造方法结束。

    public Optional<EndpointConfiguration> findById(long id) { // 按内部 ID 查询接收端。
        return endpoints.findById(id); // 不存在时保留空结果，由 API 层决定返回 404。
    } // 详情查询结束。

    public EndpointPage list(int page, int size) { // 查询指定页码和每页条数。
        long offset = ((long) page - 1L) * size; // 使用 long 计算偏移量，避免整数乘法溢出。
        long total = endpoints.countAll(); // 查询接收端总数。
        List<EndpointConfiguration> items = endpoints.findPage(size, offset); // 按稳定排序读取本页数据。
        return new EndpointPage(page, size, total, items); // 返回页码、条数、总数和结果。
    } // 分页查询结束。

    public record EndpointPage( // 将一次分页查询的结果交给 API 层。
            int page, // 从 1 开始的当前页码。
            int size, // 本次查询使用的每页条数。
            long total, // 数据库中的接收端总数。
            List<EndpointConfiguration> items // 当前页不含密钥的配置集合。
    ) { // record 提供各字段的只读访问方法。
    } // 分页结果类型结束。
} // 接收端查询服务结束。