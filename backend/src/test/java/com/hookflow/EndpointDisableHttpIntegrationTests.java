package com.hookflow;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class EndpointDisableHttpIntegrationTests {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    static ConfigurableApplicationContext context;
    static JdbcTemplate jdbc;
    static JsonMapper mapper;
    static HttpClient client;
    static String base;
    static String token;
    static String key;

    @BeforeAll static void start() throws Exception {
        byte[] encryptionKey=new byte[32];new java.security.SecureRandom().nextBytes(encryptionKey);
        context=new SpringApplicationBuilder(HookflowBackendApplication.class).run(
                "--spring.profiles.active=p2-login-test","--server.port=0",
                "--DB_URL="+MYSQL.getJdbcUrl(),"--DB_USERNAME="+MYSQL.getUsername(),"--DB_PASSWORD="+MYSQL.getPassword(),
                "--HOOKFLOW_ADMIN_USERNAME=disable-http-admin","--HOOKFLOW_ADMIN_PASSWORD=disable-http-password",
                "--HOOKFLOW_SECRET_ENCRYPTION_KEY="+Base64.getEncoder().encodeToString(encryptionKey));
        jdbc=context.getBean(JdbcTemplate.class);mapper=context.getBean(JsonMapper.class);
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        base="http://127.0.0.1:"+context.getEnvironment().getRequiredProperty("local.server.port");
        var login=post("/api/v1/auth/login",null,null,"{\"username\":\"disable-http-admin\",\"password\":\"disable-http-password\"}");
        assertEquals(200,login.statusCode());token=json(login).path("token").asString();
        var rotation=post("/api/v1/admin/api-key/rotate",token,null,null);
        assertEquals(200,rotation.statusCode());key=json(rotation).path("apiKey").asString();
    }
    @AfterAll static void stop(){if(context!=null)context.close();}

    @Test void actualDisableEnforcesIdentityDeduplicationAndAtomicSuccessAuditWithoutSecrets() throws Exception {
        var creation=post("/api/v1/admin/endpoints",token,null,
                "{\"name\":\"disable-http\",\"targetType\":\"EXTERNAL\",\"url\":\"https://hooks.example.com/hook\"}");
        assertEquals(201,creation.statusCode());long ep=json(creation).path("id").asLong();
        String secret=json(creation).path("signingSecret").asString();
        String path="/api/v1/admin/endpoints/"+ep+"/disable";
        String eventId="disable-http-"+UUID.randomUUID();String body=event(eventId,ep,1);
        var submitted=post("/api/v1/events",null,key,body);assertEquals(202,submitted.statusCode());
        long eventPk=jdbc.queryForObject("SELECT id FROM event WHERE event_id=?",Long.class,eventId);
        long task=jdbc.queryForObject("SELECT id FROM delivery_task WHERE event_pk=?",Long.class,eventPk);
        LocalDateTime due=jdbc.queryForObject("SELECT next_run_at FROM delivery_task WHERE id=?",(rs,row)->rs.getObject(1,LocalDateTime.class),task);
        error(post(path,null,null,null),401,"UNAUTHORIZED");
        error(post(path,null,key,null),403,"FORBIDDEN");
        for(String invalid:List.of("0","-1","abc","1.2","9223372036854775808"))
            error(post("/api/v1/admin/endpoints/"+invalid+"/disable",token,null,null),400,"INVALID_REQUEST");
        error(post("/api/v1/admin/endpoints/9223372036854775807/disable",token,null,null),404,"NOT_FOUND");
        error(post(path,token,null,"{}"),400,"INVALID_REQUEST");
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='ENDPOINT_DISABLED' AND object_id=?",Long.class,Long.toString(ep)));
        Instant before=Instant.now();var disabled=post(path,token,null,null);Instant after=Instant.now();
        assertEquals(200,disabled.statusCode());JsonNode view=json(disabled);
        assertFalse(view.path("enabled").asBoolean());assertEquals(ep,view.path("id").asLong());
        assertEquals(8,view.size());assertFalse(disabled.body().contains("secret"));assertFalse(disabled.body().contains(secret));
        byte[] ciphertext=jdbc.queryForObject("SELECT signing_secret_ciphertext FROM endpoint WHERE id=?",(rs,row)->rs.getBytes(1),ep);
        assertFalse(disabled.body().contains(Base64.getEncoder().encodeToString(ciphertext)));
        Instant updated=Instant.parse(view.path("updatedAt").asString());
        assertFalse(updated.isBefore(before.minusMillis(1)));assertFalse(updated.isAfter(after));
        assertEquals(0,updated.getNano()%1_000_000);
        String requestId=disabled.headers().firstValue("X-Request-Id").orElseThrow();
        assertNotEquals("untrusted-client-request-id",requestId);UUID.fromString(requestId);
        var audit=jdbc.queryForMap("SELECT * FROM audit_log WHERE request_id=?",requestId);
        assertEquals("ENDPOINT_DISABLED",audit.get("action"));assertEquals("ENDPOINT",audit.get("object_type"));
        assertEquals(Long.toString(ep),audit.get("object_id"));
        assertEquals(jdbc.queryForObject("SELECT id FROM app_user WHERE username='disable-http-admin'",Long.class),((Number)audit.get("actor_user_id")).longValue());
        LocalDateTime occurred=jdbc.queryForObject("SELECT occurred_at FROM audit_log WHERE request_id=?",(rs,row)->rs.getObject(1,LocalDateTime.class),requestId);
        assertEquals(updated,occurred.toInstant(ZoneOffset.UTC));
        assertEquals(occurred,jdbc.queryForObject("SELECT updated_at FROM delivery_task WHERE id=?",(rs,row)->rs.getObject(1,LocalDateTime.class),task));
        assertFalse(audit.toString().contains(secret));assertFalse(audit.toString().contains(token));assertFalse(audit.toString().contains(key));
        assertEquals("PAUSED",jdbc.queryForObject("SELECT status FROM delivery_task WHERE id=?",String.class,task));
        assertEquals("PAUSED",jdbc.queryForObject("SELECT current_status FROM event WHERE id=?",String.class,eventPk));
        assertEquals("PENDING",jdbc.queryForObject("SELECT resume_status FROM delivery_task WHERE id=?",String.class,task));
        assertEquals(due,jdbc.queryForObject("SELECT resume_due_at FROM delivery_task WHERE id=?",(rs,row)->rs.getObject(1,LocalDateTime.class),task));
        assertEquals(0,jdbc.queryForObject("SELECT attempt_count FROM delivery_task WHERE id=?",Integer.class,task));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE task_id=?",Long.class,task));
        var repeated=post(path,token,null,null);assertEquals(200,repeated.statusCode());assertEquals(view,json(repeated));
        assertEquals(due,jdbc.queryForObject("SELECT resume_due_at FROM delivery_task WHERE id=?",(rs,row)->rs.getObject(1,LocalDateTime.class),task));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='ENDPOINT_DISABLED' AND object_id=?",Long.class,Long.toString(ep)));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE request_id=?",Long.class,repeated.headers().firstValue("X-Request-Id").orElseThrow()));
        long events=jdbc.queryForObject("SELECT COUNT(*) FROM event",Long.class),tasks=jdbc.queryForObject("SELECT COUNT(*) FROM delivery_task",Long.class);
        var rejected=post("/api/v1/events",null,key,event("new-"+UUID.randomUUID(),ep,1));
        error(rejected,422,"ENDPOINT_DISABLED");
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE request_id=?",Long.class,rejected.headers().firstValue("X-Request-Id").orElseThrow()));
        var duplicate=post("/api/v1/events",null,key,body);assertEquals(200,duplicate.statusCode());
        assertTrue(json(duplicate).path("duplicate").asBoolean());assertEquals("PAUSED",json(duplicate).path("status").asString());
        error(post("/api/v1/events",null,key,event(eventId,ep,2)),409,"CONFLICT");
        assertEquals(events,jdbc.queryForObject("SELECT COUNT(*) FROM event",Long.class));assertEquals(tasks,jdbc.queryForObject("SELECT COUNT(*) FROM delivery_task",Long.class));
    }
    @Test void actualHttpAuditInsertFailureReturnsSafe500AndRollsBackEverything() throws Exception {
        var creation=post("/api/v1/admin/endpoints",token,null,"{\"name\":\"rollback-http\",\"targetType\":\"SIMULATOR\"}");
        long ep=json(creation).path("id").asLong();String eventId="rollback-"+UUID.randomUUID();
        assertEquals(202,post("/api/v1/events",null,key,event(eventId,ep,1)).statusCode());
        long eventPk=jdbc.queryForObject("SELECT id FROM event WHERE event_id=?",Long.class,eventId);
        var before=jdbc.queryForMap("SELECT enabled,updated_at FROM endpoint WHERE id=?",ep);
        jdbc.execute("ALTER TABLE audit_log ADD CONSTRAINT ck_disable_http_audit CHECK (action <> 'ENDPOINT_DISABLED' OR object_id <> '"+ep+"')");
        try {
            var failure=post("/api/v1/admin/endpoints/"+ep+"/disable",token,null,null);
            error(failure,500,"INTERNAL_ERROR");
            assertFalse(failure.body().contains("ck_disable_http_audit"));assertFalse(failure.body().contains("SQLException"));
            assertEquals(before,jdbc.queryForMap("SELECT enabled,updated_at FROM endpoint WHERE id=?",ep));
            assertEquals("PENDING",jdbc.queryForObject("SELECT current_status FROM event WHERE id=?",String.class,eventPk));
            assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM delivery_task WHERE event_pk=?",String.class,eventPk));
            assertNull(jdbc.queryForObject("SELECT resume_status FROM delivery_task WHERE event_pk=?",String.class,eventPk));
            assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE request_id=?",Long.class,failure.headers().firstValue("X-Request-Id").orElseThrow()));
        } finally {jdbc.execute("ALTER TABLE audit_log DROP CHECK ck_disable_http_audit");}
    }
    static String event(String id,long ep,int value){return "{\"eventId\":\""+id+"\",\"eventType\":\"order.paid\",\"endpointId\":"+ep+",\"payload\":{\"value\":"+value+"}}";}
    static JsonNode json(HttpResponse<String> response){return mapper.readTree(response.body());}
    static void error(HttpResponse<String> response,int status,String code){
        assertEquals(status,response.statusCode(),response.body());JsonNode body=json(response);assertEquals(code,body.path("code").asString());assertEquals(3,body.size());
        String id=response.headers().firstValue("X-Request-Id").orElseThrow();assertEquals(id,body.path("requestId").asString());assertNotEquals("untrusted-client-request-id",id);
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE request_id=?",Long.class,id));
    }
    static HttpResponse<String> post(String path,String admin,String business,String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(10)).header("X-Request-Id","untrusted-client-request-id");
        if(admin!=null)request.header("Authorization","Bearer "+admin);
        if(business!=null)request.header("X-HookFlow-Api-Key",business);
        if(body!=null)request.header("Content-Type","application/json");
        return client.send(request.POST(body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
