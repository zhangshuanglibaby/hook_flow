package com.hookflow.event.application; // 事件领取前的持锁检查属于事件应用层。

import com.hookflow.event.infrastructure.EventRepository; // 由事件模块自己的仓库执行事件行锁查询。
import java.util.Optional; // 事件不存在或关联不符时返回空结果。
import org.springframework.context.annotation.Profile; // 无数据库的 skeleton 配置不加载此服务。
import org.springframework.stereotype.Service; // 注册为可供投递应用层调用的服务。
import org.springframework.transaction.annotation.Propagation; // 要求调用方先开启事务。
import org.springframework.transaction.annotation.Transactional; // 使事件行锁加入调用方事务。

@Profile("!skeleton") // 仅在连接真实数据库的配置下启用。
@Service // 投递模块通过此应用层入口检查事件，不直接访问事件仓库。
public class EventClaimGuard { // 封装领取时的事件锁定与关联复查。
    private final EventRepository events; // 保存事件模块自己的数据库入口。

    public EventClaimGuard(EventRepository events) { // 由 Spring 注入事件仓库。
        this.events = events; // 后续使用同一仓库锁定事件。
    } // 构造方法结束。

    public Optional<Long> findEndpointIdForClaim(long eventPk) { // 非锁定定位事件所属接收端，供领取流程确定第一个行锁的目标。
        if (eventPk <= 0) { // 内部事件主键必须是正数。
            return Optional.empty(); // 无效主键不对应可领取的事件。
        } // 完成主键检查。
        return events.findEndpointId(eventPk); // 只做普通查询；后续取得接收端和事件行锁后仍须复查关联。
    } // 定位结束；此方法本身不领取任务，也不发起 HTTP。

    @Transactional(propagation = Propagation.MANDATORY) // 必须加入已锁接收端的领取事务，不自行提交。
    public Optional<ClaimEvent> lockForClaim(long eventPk, long expectedEndpointId) { // 接收非锁定定位得到的两个 ID。
        return events.findByIdForUpdate(eventPk) // 在接收端锁之后锁定事件行并取得当前值。
                .filter(event -> event.endpointId() == expectedEndpointId) // 复查关联 ID，不能信任先前的普通查询。
                .map(event -> new ClaimEvent( // 只向领取流程传递复查任务所需的字段。
                        event.id(), // 保留持锁事件的内部 ID。
                        event.endpointId(), // 保留已持锁复查的接收端 ID，供后续同事务状态更新使用。
                        event.latestRoundNo(), // 后续与持锁任务的轮次比较。
                        event.currentStatus() // 保留持锁读取的事件状态，供领取时与任务状态核对。
                )); // 不在此处锁任务、修改状态或发起 HTTP。
    } // 方法返回后，事件行锁仍由调用方事务持有。

    @Transactional(propagation = Propagation.MANDATORY) // 必须加入调用方已开启的领取事务，不能单独提交事件状态。
    public boolean markDelivering(ClaimEvent event) { // 接收本事务中已锁定并复查过的事件数据。
        if (event == null) { // 缺少事件数据时不能确定要更新哪一行。
            throw new IllegalArgumentException("领取事件缺失"); // 拒绝不完整的内部调用。
        } // 完成空值检查。
        EventRepository.LockedEvent lockedEvent = new EventRepository.LockedEvent( // 转为事件仓库已有的内部数据类型。
                event.eventPk(), // 使用已锁定事件的数据库主键。
                event.endpointId(), // 使用取得事件行锁后复查过的接收端 ID。
                event.latestRoundNo(), // 使用已锁定事件的最新轮号。
                event.currentStatus() // 使用已锁定事件的领取前状态。
        ); // 不重新查询或锁定其他表，维持接收端、事件、任务的锁顺序。
        return events.markDelivering(lockedEvent, event.currentStatus()) == 1; // 条件更新恰好一行才算成功；调用方须在失败时回滚整个领取事务。
    } // 事件更新与任务更新、尝试插入由同一外层事务统一提交。

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markDeliveryCompleted(ClaimEvent event, int roundNo, String nextStatus,
            java.time.LocalDateTime completedAt) { // 状态变动与任务、尝试共用完成事务。
        return events.markDeliveryCompleted(new EventRepository.LockedEvent(event.eventPk(),
                event.endpointId(), event.latestRoundNo(), event.currentStatus()),
                roundNo, nextStatus, completedAt) == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markPaused(ClaimEvent event, int roundNo, String expectedStatus) {
        if (event.latestRoundNo() != roundNo || !expectedStatus.equals(event.currentStatus())
                || !("PENDING".equals(expectedStatus) || "WAIT_RETRY".equals(expectedStatus))) {
            throw new IllegalArgumentException("事件暂停状态无效");
        }
        return events.markPaused(new EventRepository.LockedEvent(event.eventPk(), event.endpointId(),
                event.latestRoundNo(), event.currentStatus()), roundNo, expectedStatus) == 1;
    }

    public java.util.Optional<EventRepository.DeliveryPayload> findDeliveryPayload(long eventPk) {
        return events.findDeliveryPayload(eventPk); // 供提交后的投递工作读取原规范化正文。
    }

    public record ClaimEvent(long eventPk, long endpointId, // 保存持锁事件的主键及已复查的接收端 ID。
        int latestRoundNo, String currentStatus) { // 保存持锁读取的最新轮号和当前状态。
    } // 领取用事件数据定义结束。
} // 事件领取检查服务结束。