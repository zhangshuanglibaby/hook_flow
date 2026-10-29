package com.hookflow.delivery.application;

import com.hookflow.delivery.application.DeliveryClaimService.ClaimedTask;
import com.hookflow.delivery.infrastructure.DueDeliveryTaskRepository;
import com.hookflow.delivery.infrastructure.SingleDeliveryHttpClient;
import com.hookflow.delivery.infrastructure.SingleDeliveryHttpResult;
import com.hookflow.endpoint.infrastructure.EndpointSigningSecretCipher;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Profile("!skeleton")
@Service
public class DeliveryExecutionService { // 只由领取事务提交之后的有界工作线程调用。
    private final DueDeliveryTaskRepository tasks;
    private final SingleDeliveryHttpClient client;
    private final EndpointSigningSecretCipher secrets;
    private final DeliveryCompletionService completions;
    private final Clock clock;

    public DeliveryExecutionService(DueDeliveryTaskRepository tasks, SingleDeliveryHttpClient client,
            EndpointSigningSecretCipher secrets, DeliveryCompletionService completions, Clock clock) {
        this.tasks = tasks;
        this.client = client;
        this.secrets = secrets;
        this.completions = completions;
        this.clock = clock;
    }

    public void execute(ClaimedTask claim) { // HTTP 和解密操作均不在数据库写事务内。
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("不能在数据库事务中执行 HTTP");
        }
        var snapshot = tasks.findDeliverySnapshot(claim.taskId());
        if (snapshot.isEmpty()) {
            return; // 任务已消失，不对不存在的数据发请求。
        }
        var task = snapshot.get();
        if (!"DELIVERING".equals(task.status()) || task.attemptCount() != claim.attemptNo()
                || !claim.claimToken().equals(task.claimToken()) || task.leaseUntil() == null
                || !task.leaseUntil().isAfter(LocalDateTime.ofInstant(
                        clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC))) {
            return; // 快照与本次领取不一致时不发送迟到请求。
        }
        String secret;
        try {
            secret = secrets.decryptForDelivery(task.ciphertext()); // 密文损坏可以确定请求尚未发送。
        } catch (IllegalStateException | IllegalArgumentException exception) {
            completions.complete(claim.taskId(), claim.attemptNo(), claim.claimToken(),
                    SingleDeliveryHttpResult.localFailure());
            return;
        }
        SingleDeliveryHttpResult result;
        try {
            byte[] body = task.payloadJson().getBytes(StandardCharsets.UTF_8);
            result = switch (task.targetType()) {
                case "EXTERNAL" -> client.postOnce(task.url(), task.eventId(), body, secret, task.timeoutMs());
                case "SIMULATOR" -> client.postSimulatorOnce(task.eventId(), body, secret, task.timeoutMs());
                default -> SingleDeliveryHttpResult.localFailure();
            };
        } catch (RuntimeException exception) { // 调用已进入客户端后不能证明请求未发出。
            result = SingleDeliveryHttpResult.unknown(); // 保守记录结果未知，不保存异常原文。
        }
        completions.complete(claim.taskId(), claim.attemptNo(), claim.claimToken(), result);
    }
}
