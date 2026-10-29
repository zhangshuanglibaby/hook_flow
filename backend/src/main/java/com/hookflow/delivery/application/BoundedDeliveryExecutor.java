package com.hookflow.delivery.application; // 有界投递执行器属于投递应用层。

import java.util.Optional; // 用空结果表示当前没有可预留的执行容量。
import java.util.concurrent.ArrayBlockingQueue; // 使用固定容量的等待队列。
import java.util.concurrent.RejectedExecutionException; // 识别关闭期间的提交拒绝。
import java.util.concurrent.Semaphore; // 在领取数据库任务之前预留执行容量。
import java.util.concurrent.ThreadPoolExecutor; // 创建固定线程数的执行器。
import java.util.concurrent.TimeUnit; // 指定线程池空闲时间的单位。
import org.springframework.context.annotation.Profile; // 避免无数据库骨架配置启动投递线程。
import org.springframework.stereotype.Component; // 将执行器交给 Spring 管理。

@Profile("!skeleton") // 骨架测试不需要正式投递执行器。
@Component // 正式应用使用同一个有界执行器实例。
public final class BoundedDeliveryExecutor implements AutoCloseable { // 负责容量预留、提交和关闭。
    public static final int WORKER_COUNT = 4; // 固定使用四个 HTTP 投递工作线程。
    public static final int QUEUE_CAPACITY = 16; // 最多允许十六个已提交工作排队。
    private final Semaphore capacity = new Semaphore(WORKER_COUNT + QUEUE_CAPACITY); // 同时限制运行、排队和预留中的工作。
    private final ThreadPoolExecutor executor; // 保存实际运行阻塞式 HTTP 工作的固定线程池。

    public BoundedDeliveryExecutor() { // 创建线程池时明确指定全部容量边界。
        this.executor = new ThreadPoolExecutor( // 不使用可能无限增长的默认队列。
                WORKER_COUNT, // 核心线程数固定为四。
                WORKER_COUNT, // 最大线程数也固定为四。
                0L, // 核心线程不会因空闲而缩减。
                TimeUnit.MILLISECONDS, // 空闲时间参数使用毫秒单位。
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), // 等待队列最多保存十六个工作。
                new ThreadPoolExecutor.AbortPolicy() // 满载或关闭时拒绝提交，绝不在扫描线程执行 HTTP。
        ); // 固定线程池创建完成。
        // 核心线程由前四次提交直接启动，避免预启动线程尚未取任务时队列提前塞满。
    } // 构造方法结束。

    public Optional<Reservation> reserve() { // 扫描器应在领取数据库任务之前调用。
        if (executor.isShutdown() || !capacity.tryAcquire()) { // 关闭或满载时均不等待。
            return Optional.empty(); // 没有容量就不应领取任务。
        } // 已取得一个覆盖运行、排队及暂未提交阶段的许可。
        Reservation reservation = new Reservation(); // 为这一次候选任务建立独立预留凭证。
        if (executor.isShutdown()) { // 处理取得许可的同时应用开始关闭的竞争。
            reservation.close(); // 归还尚未用于领取的许可。
            return Optional.empty(); // 不允许关闭中的执行器继续接收任务。
        } // 执行器仍可用；后续提交时还会处理关闭竞争。
        return Optional.of(reservation); // 调用方必须关闭未使用的预留凭证。
    } // 非阻塞容量预留结束。

    public int workerCount() { // 供容量验证读取正式配置的线程上限。
        return executor.getMaximumPoolSize(); // 返回固定的四线程上限。
    } // 线程上限查询结束。

    public int queueCapacity() { // 供容量验证读取正式配置的队列上限。
        return QUEUE_CAPACITY; // 返回固定的十六个排队位置。
    } // 队列上限查询结束。

    public final class Reservation implements AutoCloseable { // 表示领取前已取得的一份容量。
        private boolean submitted; // 标记该容量是否已交给执行线程负责归还。
        private boolean released; // 防止异常或重复关闭造成许可重复归还。

        private Reservation() { // 仅允许外层执行器在取得许可后创建凭证。
        } // 凭证构造结束。

        public synchronized void submit(Runnable work) { // 只在领取事务成功提交之后调用。
            if (work == null) { // 不允许提交不存在的投递工作。
                throw new IllegalArgumentException("投递工作缺失"); // 保留凭证供调用方关闭归还。
            } // 工作参数检查结束。
            if (submitted || released) { // 一份预留容量只能使用一次。
                throw new IllegalStateException("投递容量已使用或已释放"); // 阻止重复发送。
            } // 凭证状态检查结束。
            submitted = true; // 从此由执行任务的 finally 负责归还许可。
            try { // 提交到固定大小且队列有界的线程池。
                executor.execute(() -> { // 线程池不会把工作转给扫描线程执行。
                    try { // 保证成功或失败都归还这一份容量。
                        work.run(); // 真正的 HTTP 与结果写回由后续步骤接入。
                    } finally { // 即使工作抛出异常也不能永久占用容量。
                        release(); // 工作结束后归还许可。
                    } // 执行工作结束。
                }); // 只有成功入池才可能开始实际执行。
            } catch (RejectedExecutionException exception) { // 应用关闭竞争可能拒绝提交。
                release(); // 未被接受的工作立即归还许可。
                throw exception; // 交由调用方识别，不伪装成已安排投递。
            } // 提交处理结束。
        } // 一次性工作提交结束。

        private synchronized void release() { // 集中处理许可归还。
            if (!released) { // 多条清理路径最多只能归还一次。
                released = true; // 先记状态，再归还容量。
                capacity.release(); // 允许扫描器在后续周期考虑新任务。
            } // 已归还时不重复增加执行容量。
        } // 许可归还结束。

        @Override // 未成功领取任务时可由 try-with-resources 自动清理。
        public synchronized void close() { // 关闭未提交的预留凭证。
            if (!submitted) { // 已提交工作必须等其执行完毕后归还许可。
                release(); // 未提交时立即释放预留容量。
            } // 不抢先释放仍在运行或排队的工作容量。
        } // 凭证关闭结束。
    } // 预留凭证定义结束。

    @Override // Spring 关闭组件时释放线程池资源。
    public void close() { // 不接受新的投递工作，让已提交工作按队列顺序结束。
        executor.shutdown(); // 不在数据库事务内等待网络或线程退出。
    } // 执行器关闭结束。
} // 有界投递执行器定义结束。