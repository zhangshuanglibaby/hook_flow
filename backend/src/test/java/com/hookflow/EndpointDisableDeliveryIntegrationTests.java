package com.hookflow;

import static org.junit.jupiter.api.Assertions.*;

import com.hookflow.delivery.application.BoundedDeliveryExecutor;
import com.hookflow.delivery.application.DueDeliveryScanner;
import com.hookflow.delivery.infrastructure.RedisDeliveryWakeup;
import com.hookflow.delivery.infrastructure.SimulatorInternalTargetRoute;
import com.hookflow.endpoint.application.EndpointDisableService;
import com.hookflow.endpoint.application.EndpointSecretProvisioningService;
import com.hookflow.event.application.EventCreationService;
import com.hookflow.event.domain.EventPayloadCanonicalizer;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import redis.clients.jedis.Jedis;
import tools.jackson.databind.node.JsonNodeFactory;

@Testcontainers
class EndpointDisableDeliveryIntegrationTests {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>(
            "redis@sha256:aa049e689e141a4358ad1d4562dc49c88a89fbab711fd8fcc33f684c80b26301").withExposedPorts(6379);
    static int receiverPort;

    @TestConfiguration(proxyBeanMethods = false)
    static class ReceiverRoute {
        @Bean @Primary SimulatorInternalTargetRoute fixtureRoute() {
            return new SimulatorInternalTargetRoute(new MockEnvironment().withProperty("server.port",Integer.toString(receiverPort)));
        }
    }

    @Test void actualInFlightHttpCanFinishButPausedUnsentTasksIgnoreScansAndRepeatedRedisSignals() throws Exception {
        for (int status : List.of(200,500)) {
            CountDownLatch received=new CountDownLatch(1),respond=new CountDownLatch(1);
            AtomicInteger requests=new AtomicInteger();
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            var httpWorkers=Executors.newFixedThreadPool(2);
            server.setExecutor(httpWorkers);
            server.createContext(SimulatorInternalTargetRoute.PATH,exchange->{
                try {
                    exchange.getRequestBody().readAllBytes();requests.incrementAndGet();received.countDown();
                    if(!respond.await(12,TimeUnit.SECONDS))throw new IllegalStateException("测试响应等待超时");
                    byte[] body="controlled".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);
                } catch(InterruptedException e) {Thread.currentThread().interrupt();}
                finally {exchange.close();}
            });
            server.start();receiverPort=server.getAddress().getPort();
            try (var context=start()) {
                JdbcTemplate jdbc=context.getBean(JdbcTemplate.class);
                long actor=jdbc.queryForObject("SELECT id FROM app_user WHERE username='disable-delivery-admin'",Long.class);
                long ep=context.getBean(EndpointSecretProvisioningService.class).createSimulator("in-flight",15000,true,actor,id()).id();
                var creator=context.getBean(EventCreationService.class);
                var payload=new EventPayloadCanonicalizer().canonicalize(JsonNodeFactory.instance.nullNode());
                var executor=context.getBean(BoundedDeliveryExecutor.class);
                List<BoundedDeliveryExecutor.Reservation> held=new ArrayList<>();
                for(int i=0;i<20;i++)held.add(executor.reserve().orElseThrow());
                String active=id();creator.create(active,"order.paid",ep,payload).orElseThrow();
                // 首条任务仅在显式释放一个执行许可后开始，后续未发任务一直没有领取容量。
                held.removeLast().close();context.getBean(DueDeliveryScanner.class).scanDueTasks();
                assertTrue(received.await(5,TimeUnit.SECONDS));
                long activeTask=taskId(jdbc,active);
                var claimed=jdbc.queryForMap("SELECT claim_token,lease_until FROM delivery_task WHERE id=?",activeTask);
                assertEquals("DELIVERING",taskState(jdbc,activeTask));
                assertEquals("STARTED",jdbc.queryForObject("SELECT result FROM delivery_attempt WHERE task_id=?",String.class,activeTask));
                String unsent=id();creator.create(unsent,"order.paid",ep,payload).orElseThrow();
                long unsentTask=taskId(jdbc,unsent);
                long started=System.nanoTime();
                context.getBean(EndpointDisableService.class).disable(ep,actor,id()).orElseThrow();
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<3000,"停用不能等待进行中的 HTTP");
                assertEquals(1L,respond.getCount());
                assertEquals(claimed,jdbc.queryForMap("SELECT claim_token,lease_until FROM delivery_task WHERE id=?",activeTask));
                assertEquals("PAUSED",taskState(jdbc,unsentTask));
                assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE task_id=?",Long.class,unsentTask));
                held.forEach(BoundedDeliveryExecutor.Reservation::close);held.clear();
                try(Jedis redis=new Jedis(REDIS.getHost(),REDIS.getMappedPort(6379))) {
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                    while(redis.pubsubNumSub(RedisDeliveryWakeup.CHANNEL).get(RedisDeliveryWakeup.CHANNEL)<1 && System.nanoTime()<deadline)Thread.sleep(20);
                    assertTrue(redis.pubsubNumSub(RedisDeliveryWakeup.CHANNEL).get(RedisDeliveryWakeup.CHANNEL)>=1);
                    for(int i=0;i<40;i++)assertTrue(redis.publish(RedisDeliveryWakeup.CHANNEL,RedisDeliveryWakeup.SIGNAL)>=1);
                }
                for(int i=0;i<4;i++)context.getBean(DueDeliveryScanner.class).scanDueTasks();
                respond.countDown();
                String expected=status==200?"SUCCESS":"PAUSED";
                await(()->expected.equals(taskState(jdbc,activeTask)));
                assertEquals(expected,jdbc.queryForObject("SELECT current_status FROM event WHERE event_id=?",String.class,active));
                assertEquals(status==200?"SUCCESS":"HTTP_FAILURE",jdbc.queryForObject("SELECT result FROM delivery_attempt WHERE task_id=?",String.class,activeTask));
                assertEquals(status,jdbc.queryForObject("SELECT http_status FROM delivery_attempt WHERE task_id=?",Integer.class,activeTask));
                assertEquals(1,jdbc.queryForObject("SELECT attempt_count FROM delivery_task WHERE id=?",Integer.class,activeTask));
                if(status==500) {
                    assertEquals("WAIT_RETRY",jdbc.queryForObject("SELECT resume_status FROM delivery_task WHERE id=?",String.class,activeTask));
                    LocalDateTime ended=jdbc.queryForObject("SELECT ended_at FROM delivery_attempt WHERE task_id=?",(rs,row)->rs.getObject(1,LocalDateTime.class),activeTask);
                    assertEquals(ended.plusSeconds(10),jdbc.queryForObject("SELECT resume_due_at FROM delivery_task WHERE id=?",(rs,row)->rs.getObject(1,LocalDateTime.class),activeTask));
                }
                Thread.sleep(250);
                context.getBean(DueDeliveryScanner.class).scanDueTasks();
                assertEquals(1,requests.get());assertEquals("PAUSED",taskState(jdbc,unsentTask));
                assertEquals("PAUSED",jdbc.queryForObject("SELECT current_status FROM event WHERE event_id=?",String.class,unsent));
                assertEquals(0,jdbc.queryForObject("SELECT attempt_count FROM delivery_task WHERE id=?",Integer.class,unsentTask));
                assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE task_id=?",Long.class,unsentTask));
                assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE task_id=?",Long.class,activeTask));
            } finally {
                respond.countDown();server.stop(0);httpWorkers.shutdownNow();assertTrue(httpWorkers.awaitTermination(5,TimeUnit.SECONDS));
            }
        }
    }
    static String id(){return UUID.randomUUID().toString();}
    static long taskId(JdbcTemplate jdbc,String id){return jdbc.queryForObject("SELECT t.id FROM delivery_task t JOIN event e ON e.id=t.event_pk WHERE e.event_id=?",Long.class,id);}
    static String taskState(JdbcTemplate jdbc,long id){return jdbc.queryForObject("SELECT status FROM delivery_task WHERE id=?",String.class,id);}
    static void await(java.util.function.BooleanSupplier condition)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(20);
        assertTrue(condition.getAsBoolean());
    }
    static ConfigurableApplicationContext start(){
        byte[] key=new byte[32];new SecureRandom().nextBytes(key);
        return new SpringApplicationBuilder(HookflowBackendApplication.class,ReceiverRoute.class).run(
                "--spring.profiles.active=p2-login-test","--server.port=0",
                "--DB_URL="+MYSQL.getJdbcUrl(),"--DB_USERNAME="+MYSQL.getUsername(),"--DB_PASSWORD="+MYSQL.getPassword(),
                "--HOOKFLOW_ADMIN_USERNAME=disable-delivery-admin","--HOOKFLOW_ADMIN_PASSWORD=disable-delivery-password",
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY="+Base64.getEncoder().encodeToString(key),
                "--HOOKFLOW_REDIS_ENABLED=true","--REDIS_HOST="+REDIS.getHost(),"--REDIS_PORT="+REDIS.getMappedPort(6379));
    }
}
