-- 创建管理员账户表；后续登录功能会读取这里的账户信息。
CREATE TABLE app_user (
    -- 数据库内部主键，由 MySQL 自动生成。
    id BIGINT NOT NULL AUTO_INCREMENT,
    -- 登录用户名；最长 64 个字符，与接口契约一致。
    username VARCHAR(64) NOT NULL,
    -- 只保存密码摘要，不保存明文密码。
    password_hash VARCHAR(255) NOT NULL,
    -- 保存账户角色；具体角色值由后续认证功能写入。
    role VARCHAR(32) NOT NULL,
    -- 账户创建时间；后续应用以 UTC 写入，保留毫秒精度。
    created_at DATETIME(3) NOT NULL,
    -- 用内部 ID 唯一标识一行账户。
    PRIMARY KEY (id),
    -- 数据库拒绝重复用户名。
    CONSTRAINT uk_app_user_username UNIQUE (username)
-- 使用支持事务和外键的 InnoDB，并指定 utf8mb4 字符集。
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;




-- 创建管理员登录会话表。
CREATE TABLE admin_session (
    -- 会话的数据库内部主键。
    id BIGINT NOT NULL AUTO_INCREMENT,
    -- 所属管理员账户，必须能在 app_user 表中找到。
    user_id BIGINT NOT NULL,
    -- 仅保存 Token 的哈希字节，不保存可用于登录的明文 Token。
    token_hash VARBINARY(255) NOT NULL,
    -- Token 到期时间；由应用按 UTC 写入。
    expires_at DATETIME(3) NOT NULL,
    -- 尚未主动退出时为空；退出撤销后记录 UTC 时间。
    revoked_at DATETIME(3) NULL,
    -- 用内部 ID 唯一标识会话。
    PRIMARY KEY (id),
    -- 禁止两条会话使用相同的 Token 哈希。
    CONSTRAINT uk_admin_session_token_hash UNIQUE (token_hash),
    -- 为按账户查找会话及外键检查建立索引。
    INDEX idx_admin_session_user_id (user_id),
    -- 禁止会话引用不存在的账户，也不级联删除账户。
    CONSTRAINT fk_admin_session_user FOREIGN KEY (user_id)
        REFERENCES app_user (id) ON DELETE RESTRICT
-- 使用 InnoDB 和 utf8mb4，与第一张表保持一致。
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;



-- 创建业务 API Key 表。
CREATE TABLE api_key (
    -- 业务 Key 记录的数据库内部主键。
    id BIGINT NOT NULL AUTO_INCREMENT,
    -- 仅保存业务 Key 的哈希字节，不保存明文 Key。
    key_hash VARBINARY(255) NOT NULL,
    -- 创建时间；由应用按 UTC 写入。
    created_at DATETIME(3) NOT NULL,
    -- 未撤销时为空；轮换并撤销旧 Key 时记录 UTC 时间。
    revoked_at DATETIME(3) NULL,
    -- 用内部 ID 唯一标识一条记录。
    PRIMARY KEY (id),
    -- 防止同一 Key 哈希被重复保存。
    CONSTRAINT uk_api_key_key_hash UNIQUE (key_hash)
-- 使用支持事务的 InnoDB，并统一指定 utf8mb4 字符集。
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- 创建接收端配置表；配置不提供硬删除接口。
CREATE TABLE endpoint (
    -- 接收端的数据库内部主键。
    id BIGINT NOT NULL AUTO_INCREMENT,
    -- 管理界面显示的名称，最长 100 个字符。
    name VARCHAR(100) NOT NULL,
    -- EXTERNAL 表示外部目标；SIMULATOR 表示内置模拟器。
    target_type VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL,
    -- 外部目标地址；模拟器必须留空，由应用使用固定内部路径。
    url VARCHAR(2048) NULL,
    -- 单次请求超时，单位毫秒；允许范围为 1～15000。
    timeout_ms INT NOT NULL,
    -- 1 表示启用，0 表示停用；新接收端默认启用。
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    -- 接收端独立签名密钥的密文；不得存储明文。
    signing_secret_ciphertext VARBINARY(2048) NOT NULL,
    -- 创建时间，由应用按 UTC 写入。
    created_at DATETIME(3) NOT NULL,
    -- 最近修改时间，由应用按 UTC 写入。
    updated_at DATETIME(3) NOT NULL,
    -- 用内部 ID 唯一标识接收端。
    PRIMARY KEY (id),
    -- 支持按创建时间和 ID 稳定排序。
    INDEX idx_endpoint_created_at_id (created_at, id),
    -- 拒绝契约之外的目标类型。
    CONSTRAINT ck_endpoint_target_type CHECK (target_type IN ('EXTERNAL', 'SIMULATOR')),
    -- 外部目标必须有 URL，模拟器则不得保存用户提供的 URL。
    CONSTRAINT ck_endpoint_url_by_type CHECK (
        (target_type = 'EXTERNAL' AND url IS NOT NULL)
        OR (target_type = 'SIMULATOR' AND url IS NULL)
    ),
    -- 将超时限制在接口契约允许的范围内。
    CONSTRAINT ck_endpoint_timeout_ms CHECK (timeout_ms BETWEEN 1 AND 15000),
    -- 防止启停字段出现 0、1 以外的值。
    CONSTRAINT ck_endpoint_enabled CHECK (enabled IN (0, 1))
-- 使用 InnoDB 和 utf8mb4，与前面的表保持一致。
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- 创建事件表；一条事件可对应多个投递轮次。
CREATE TABLE event (
    -- 数据库内部主键；与调用方传入的 event_id 分开。
    id BIGINT NOT NULL AUTO_INCREMENT,
    -- 调用方事件 ID；按字符精确比较，最长 128 个字符。
    event_id VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    -- 事件类型；去重时也按字符精确比较。
    event_type VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    -- 保存应用生成的规范化 JSON 文本，不让数据库重新序列化它。
    payload_json LONGTEXT NOT NULL,
    -- 保存规范化 JSON 的 32 字节 SHA-256 指纹。
    payload_fingerprint BINARY(32) NOT NULL,
    -- 事件关联的接收端，不能引用不存在的接收端。
    endpoint_id BIGINT NOT NULL,
    -- 最新投递轮次；新事件从第 1 轮开始。
    latest_round_no INT NOT NULL,
    -- 最新轮任务状态，供列表和统计读取。
    current_status VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL,
    -- 事件创建时间；由应用按 UTC 写入。
    created_at DATETIME(3) NOT NULL,
    -- 最新轮进入成功或最终失败时的 UTC 时间；未终结时为空。
    terminal_at DATETIME(3) NULL,
    -- 用内部 ID 唯一标识一条事件。
    PRIMARY KEY (id),
    -- 并发提交同一事件 ID 时，由数据库阻止产生第二条事件。
    CONSTRAINT uk_event_event_id UNIQUE (event_id),
    -- 支持按创建时间、内部 ID 稳定分页。
    INDEX idx_event_created_at_id (created_at, id),
    -- 支持按事件类型和创建时间筛选。
    INDEX idx_event_type_created_at (event_type, created_at),
    -- 支持按最新状态和创建时间筛选。
    INDEX idx_event_status_created_at (current_status, created_at),
    -- 支持按接收端和创建时间筛选，同时供外键使用。
    INDEX idx_event_endpoint_created_at (endpoint_id, created_at),
    -- 保证文本内容是合法 JSON；具体规范化规则由后续应用实现。
    CONSTRAINT ck_event_payload_json CHECK (JSON_VALID(payload_json)),
    -- 轮次必须从 1 开始，不能为零或负数。
    CONSTRAINT ck_event_latest_round_no CHECK (latest_round_no >= 1),
    -- 只允许 P0-01 契约中的六种任务状态。
    CONSTRAINT ck_event_current_status CHECK (
        current_status IN ('PENDING', 'DELIVERING', 'WAIT_RETRY',
                           'PAUSED', 'SUCCESS', 'FINAL_FAILED')
    ),
    -- 只有最新轮处于终态时才保留终态时间。
    CONSTRAINT ck_event_terminal_at CHECK (
        (current_status IN ('SUCCESS', 'FINAL_FAILED') AND terminal_at IS NOT NULL)
        OR (current_status NOT IN ('SUCCESS', 'FINAL_FAILED') AND terminal_at IS NULL)
    ),
    -- 禁止事件引用不存在的接收端，也不级联删除接收端。
    CONSTRAINT fk_event_endpoint FOREIGN KEY (endpoint_id)
        REFERENCES endpoint (id) ON DELETE RESTRICT
-- 使用 InnoDB 和 utf8mb4。
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- 创建投递任务表；同一事件每次人工重投会新增一轮。
CREATE TABLE delivery_task (
    -- 任务的数据库内部主键。
    id BIGINT NOT NULL AUTO_INCREMENT,
    -- 所属事件的内部主键。
    event_pk BIGINT NOT NULL,
    -- 该事件的投递轮次，从 1 开始。
    round_no INT NOT NULL,
    -- 本轮状态，使用 P0-01 约定的大写值。
    status VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL,
    -- 本轮已经领取的尝试次数，创建时为 0。
    attempt_count INT NOT NULL DEFAULT 0,
    -- 本轮最多尝试次数，创建时从配置复制。
    max_attempts INT NOT NULL,
    -- 第一次可重试失败后的等待秒数，允许秒的小数部分。
    retry_delay_1_s DECIMAL(30,9) NOT NULL,
    -- 第二次可重试失败后的等待秒数，允许秒的小数部分。
    retry_delay_2_s DECIMAL(30,9) NOT NULL,
    -- 待执行或待重试的到期时间；其他状态可为空。
    next_run_at DATETIME(3) NULL,
    -- 暂停前的 PENDING 或 WAIT_RETRY 状态；非暂停时为空。
    resume_status VARCHAR(16) COLLATE utf8mb4_0900_bin NULL,
    -- 暂停前保存的到期时间；非暂停时为空。
    resume_due_at DATETIME(3) NULL,
    -- 进行中任务的租约到期时间；尚未领取时为空。
    lease_until DATETIME(3) NULL,
    -- 本次领取的令牌；尚未领取时为空，精确区分大小写。
    claim_token VARCHAR(64) COLLATE utf8mb4_0900_bin NULL,
    -- 创建本轮时复制的接收端目标类型。
    target_type_snapshot VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL,
    -- 创建本轮时复制的外部 URL；模拟器任务为空。
    url_snapshot VARCHAR(2048) NULL,
    -- 创建本轮时复制的请求超时毫秒数。
    timeout_ms_snapshot INT NOT NULL,
    -- 创建本轮时复制的签名密钥密文，不保存明文。
    secret_ciphertext_snapshot VARBINARY(2048) NOT NULL,
    -- 本轮任务创建时间，由应用按 UTC 写入。
    created_at DATETIME(3) NOT NULL,
    -- 本轮任务最近修改时间，由应用按 UTC 写入。
    updated_at DATETIME(3) NOT NULL,
    -- 本轮进入终态的时间；未终结时为空。
    finished_at DATETIME(3) NULL,
     -- 用内部 ID 唯一标识一轮任务。
    PRIMARY KEY (id),
    -- 同一事件的同一轮次只能有一条任务；此唯一索引也支持按事件和轮次查询。
    CONSTRAINT uk_delivery_task_event_round UNIQUE (event_pk, round_no),
    -- 支持扫描到期的待投递和待重试任务。
    INDEX idx_delivery_task_status_next_run (status, next_run_at, id),
    -- 支持扫描租约已到期的投递中任务。
    INDEX idx_delivery_task_status_lease (status, lease_until, id),
    -- 轮次必须从 1 开始。
    CONSTRAINT ck_delivery_task_round CHECK (round_no >= 1),
    -- 状态只允许 P0-01 契约中的六种值。
    CONSTRAINT ck_delivery_task_status CHECK (
        status IN ('PENDING', 'DELIVERING', 'WAIT_RETRY',
                   'PAUSED', 'SUCCESS', 'FINAL_FAILED')
    ),
    -- 每轮最多尝试 1～3 次，包含首次投递。
    CONSTRAINT ck_delivery_task_max_attempts CHECK (max_attempts BETWEEN 1 AND 3),
    -- 已领取次数不能为负，也不能超过本轮上限。
    CONSTRAINT ck_delivery_task_attempt_count CHECK (
        attempt_count BETWEEN 0 AND max_attempts
    ),
    -- 两档快照重试间隔都必须为正数。
    CONSTRAINT ck_delivery_task_retry_delays CHECK (
        retry_delay_1_s > 0 AND retry_delay_2_s > 0
    ),
    -- 待投递及待重试任务必须有到期时间。
    CONSTRAINT ck_delivery_task_next_run CHECK (
        status NOT IN ('PENDING', 'WAIT_RETRY') OR next_run_at IS NOT NULL
    ),
    -- 暂停时必须保存原状态和原到期时间；其他状态不得保留恢复字段。
    CONSTRAINT ck_delivery_task_resume CHECK (
        (status = 'PAUSED' AND resume_status IS NOT NULL
         AND resume_status IN ('PENDING', 'WAIT_RETRY')
         AND resume_due_at IS NOT NULL)
        OR (status <> 'PAUSED' AND resume_status IS NULL
            AND resume_due_at IS NULL)
    ),
    -- 投递中必须已有一次领取、领取令牌和未到期租约的时间值。
    CONSTRAINT ck_delivery_task_claim CHECK (
        status <> 'DELIVERING'
        OR (attempt_count > 0 AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
    ),
    -- 只允许两种接收目标快照类型。
    CONSTRAINT ck_delivery_task_target_type CHECK (
        target_type_snapshot IN ('EXTERNAL', 'SIMULATOR')
    ),
    -- 外部任务必须有 URL；模拟器任务不得使用用户 URL。
    CONSTRAINT ck_delivery_task_target_url CHECK (
        (target_type_snapshot = 'EXTERNAL' AND url_snapshot IS NOT NULL)
        OR (target_type_snapshot = 'SIMULATOR' AND url_snapshot IS NULL)
    ),
    -- 快照超时沿用接收端允许的 1～15000 毫秒范围。
    CONSTRAINT ck_delivery_task_timeout CHECK (
        timeout_ms_snapshot BETWEEN 1 AND 15000
    ),
    -- 终态必须记录完成时间；非终态不得记录完成时间。
    CONSTRAINT ck_delivery_task_finished CHECK (
        (status IN ('SUCCESS', 'FINAL_FAILED') AND finished_at IS NOT NULL)
        OR (status NOT IN ('SUCCESS', 'FINAL_FAILED') AND finished_at IS NULL)
    ),
    -- 每轮任务必须属于已有事件；清理时不能级联误删历史。
    CONSTRAINT fk_delivery_task_event FOREIGN KEY (event_pk)
        REFERENCES event (id) ON DELETE RESTRICT
-- 使用 InnoDB 和 utf8mb4。
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- 创建投递尝试表；一轮任务可以有多次尝试。
CREATE TABLE delivery_attempt (
    -- 尝试记录的数据库内部主键。
    id BIGINT NOT NULL AUTO_INCREMENT,
    -- 所属投递任务的内部主键。
    task_id BIGINT NOT NULL,
    -- 本轮内的尝试序号，从 1 开始。
    attempt_no INT NOT NULL,
    -- 初始为 STARTED，结束时更新为本次尝试的最终结果。
    result VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL,
    -- 领取并记录本次尝试的 UTC 开始时间。
    started_at DATETIME(3) NOT NULL,
    -- 尝试结束的 UTC 时间；仍为 STARTED 时为空。
    ended_at DATETIME(3) NULL,
    -- 本次尝试耗时毫秒数；仍为 STARTED 时为空。
    duration_ms BIGINT NULL,
    -- 收到最终 HTTP 响应时的状态码；无响应时为空。
    http_status SMALLINT UNSIGNED NULL,
    -- 最多保留 4096 字节的响应摘要；无摘要时为空。
    response_excerpt TEXT NULL,
    -- 简短错误代码；没有错误时为空。
    error_code VARCHAR(64) NULL,
    -- 简短错误说明；不得写入密钥或完整请求体。
    error_message VARCHAR(1024) NULL,
    -- 用于关联本次后台投递日志的请求 ID。
    request_id VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    -- 用内部 ID 唯一标识一条尝试记录。
    PRIMARY KEY (id),
    -- 同一任务的同一尝试序号只能记录一次。
    CONSTRAINT uk_delivery_attempt_task_no UNIQUE (task_id, attempt_no),
    -- 支持按尝试开始时间统计和稳定分页。
    INDEX idx_delivery_attempt_started_at_id (started_at, id),
    -- 每轮最多三次尝试，序号从 1 开始。
    CONSTRAINT ck_delivery_attempt_no CHECK (attempt_no BETWEEN 1 AND 3),
    -- 只允许 P0-01 契约中的五种尝试结果。
    CONSTRAINT ck_delivery_attempt_result CHECK (
        result IN ('STARTED', 'SUCCESS', 'HTTP_FAILURE',
                   'UNKNOWN', 'LOCAL_FAILURE')
    ),
    -- 未结束的记录没有结束时间和耗时；已结束的记录两者都必须有值。
    CONSTRAINT ck_delivery_attempt_end_fields CHECK (
        (result = 'STARTED' AND ended_at IS NULL AND duration_ms IS NULL)
        OR (result <> 'STARTED' AND ended_at IS NOT NULL AND duration_ms IS NOT NULL)
    ),
    -- 结束时间不能早于开始时间。
    CONSTRAINT ck_delivery_attempt_time_order CHECK (
        ended_at IS NULL OR ended_at >= started_at
    ),
    -- 已记录的耗时不能为负数。
    CONSTRAINT ck_delivery_attempt_duration CHECK (
        duration_ms IS NULL OR duration_ms >= 0
    ),
    -- 已记录的 HTTP 状态码必须在标准响应码范围内。
    CONSTRAINT ck_delivery_attempt_http_status CHECK (
        http_status IS NULL OR http_status BETWEEN 100 AND 599
    ),
    -- 按 UTF-8 存储后的字节数限制响应摘要，而不是按字符数限制。
    CONSTRAINT ck_delivery_attempt_excerpt CHECK (
        response_excerpt IS NULL OR OCTET_LENGTH(response_excerpt) <= 4096
    ),
    -- 尝试必须属于已有任务；清理时应先删尝试，再删任务。
    CONSTRAINT fk_delivery_attempt_task FOREIGN KEY (task_id)
        REFERENCES delivery_task (id) ON DELETE RESTRICT
-- 使用 InnoDB 和 utf8mb4。
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;



-- 创建关键管理操作记录表。
CREATE TABLE audit_log (
    -- 操作记录的数据库内部主键。
    id BIGINT NOT NULL AUTO_INCREMENT,
    -- 执行操作的管理员账户 ID。
    actor_user_id BIGINT NOT NULL,
    -- 操作名称，例如后续应用定义的接收端启停或 Key 轮换。
    action VARCHAR(64) NOT NULL,
    -- 被操作对象的类型。
    object_type VARCHAR(32) NOT NULL,
    -- 被操作对象的标识；用字符串兼容数字 ID 和事件 ID。
    object_id VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    -- 操作发生时间，由应用按 UTC 写入。
    occurred_at DATETIME(3) NOT NULL,
    -- 关联这次管理请求的请求 ID。
    request_id VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    -- 用内部 ID 唯一标识操作记录。
    PRIMARY KEY (id),
    -- 支持按操作时间和 ID 稳定分页。
    INDEX idx_audit_log_occurred_at_id (occurred_at, id),
    -- 支持按管理员定位记录，并供外键检查使用。
    INDEX idx_audit_log_actor_user_id (actor_user_id),
    -- 操作人必须是已有账户；不级联删除审计记录。
    CONSTRAINT fk_audit_log_actor FOREIGN KEY (actor_user_id)
        REFERENCES app_user (id) ON DELETE RESTRICT
-- 使用 InnoDB 和 utf8mb4。
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;



-- 模拟器的配置表；本阶段只建表，不写入配置数据。
CREATE TABLE simulator_config (
    -- 固定为 1，使这张表最多只能保存一份演示配置。
    id TINYINT UNSIGNED NOT NULL,

    -- 模拟响应模式；二进制排序规则使模式名称区分大小写。
    mode VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,

    -- FAIL_FIRST_N 模式下，前多少次请求返回失败。
    fail_first_n INT NOT NULL DEFAULT 0,

    -- PROCESSED_THEN_DELAY 模式下，处理后延迟响应的毫秒数。
    delay_ms INT NOT NULL DEFAULT 0,

    -- 配置最后更新时间，由后续应用代码写入 UTC 时间。
    updated_at DATETIME(3) NOT NULL,

    -- 配置行的主键。
    PRIMARY KEY (id),

    -- 只允许固定编号 1，配合主键限制为最多一行。
    CONSTRAINT chk_simulator_config_singleton CHECK (id = 1),

    -- 只允许契约规定的四种模拟模式。
    CONSTRAINT chk_simulator_config_mode CHECK (
        mode IN ('ALWAYS_200', 'ALWAYS_500', 'FAIL_FIRST_N', 'PROCESSED_THEN_DELAY')
    ),

    -- 失败次数限定在 0 到 10。
    CONSTRAINT chk_simulator_config_fail_count CHECK (fail_first_n BETWEEN 0 AND 10),

    -- 延迟时间限定在 0 到 30000 毫秒。
    CONSTRAINT chk_simulator_config_delay CHECK (delay_ms BETWEEN 0 AND 30000),

    -- 非 FAIL_FIRST_N 模式不得保留失败次数参数。
    CONSTRAINT chk_simulator_config_fail_mode CHECK (
        mode = 'FAIL_FIRST_N' OR fail_first_n = 0
    ),

    -- 只有延迟模式可设置延迟，且该模式必须设置正数延迟。
    CONSTRAINT chk_simulator_config_delay_mode CHECK (
        (mode = 'PROCESSED_THEN_DELAY' AND delay_ms > 0)
        OR (mode <> 'PROCESSED_THEN_DELAY' AND delay_ms = 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- 记录每个事件在模拟器中的接收次数和处理状态。
CREATE TABLE simulator_event_state (
    -- 事件的外部标识；主键保证每个 event_id 只有一条状态记录。
    event_id VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,

    -- 模拟器收到该事件的累计次数。
    received_count INT NOT NULL DEFAULT 0,

    -- 首次完成模拟处理的时间；尚未处理时为空。
    processed_at DATETIME(3) NULL,

    -- 按事件标识快速查找状态，并防止重复状态行。
    PRIMARY KEY (event_id),

    -- 接收次数不能为负数。
    CONSTRAINT chk_simulator_event_state_received_count CHECK (received_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- 记录模拟器收到的请求，供后续排查和展示使用。
CREATE TABLE simulator_request_log (
    -- 每条接收记录的自增主键。
    id BIGINT NOT NULL AUTO_INCREMENT,

    -- 请求携带的事件标识；缺失或无法识别时允许为空。
    event_id VARCHAR(128) COLLATE utf8mb4_0900_bin NULL,

    -- 模拟器收到请求的时间，由后续应用代码写入 UTC 时间。
    received_at DATETIME(3) NOT NULL,

    -- 签名是否有效：1 表示有效，0 表示无效。
    signature_valid TINYINT(1) NOT NULL,

    -- 本次计划返回的 HTTP 状态码；尚未确定时允许为空。
    planned_status SMALLINT UNSIGNED NULL,

    -- 是否因事件此前已处理而跳过重复处理：1 表示是，0 表示否。
    duplicate_processed TINYINT(1) NOT NULL DEFAULT 0,

    -- 本次请求采用的模拟模式；未进入模式处理时允许为空。
    config_mode VARCHAR(32) COLLATE utf8mb4_0900_bin NULL,

    -- 每条请求日志由独立主键标识。
    PRIMARY KEY (id),

    -- 支持按接收时间翻页查看日志。
    KEY idx_simulator_request_log_received (received_at, id),

    -- 支持查看某个事件的接收记录。
    KEY idx_simulator_request_log_event_received (event_id, received_at),

    -- 签名有效标记只允许 0 或 1。
    CONSTRAINT chk_simulator_request_log_signature CHECK (signature_valid IN (0, 1)),

    -- HTTP 状态码为空，或位于有效的三位状态码范围。
    CONSTRAINT chk_simulator_request_log_status CHECK (
        planned_status IS NULL OR planned_status BETWEEN 100 AND 599
    ),

    -- 重复处理标记只允许 0 或 1。
    CONSTRAINT chk_simulator_request_log_duplicate CHECK (duplicate_processed IN (0, 1)),

    -- 记录了模式时，必须是契约规定的四种模式之一。
    CONSTRAINT chk_simulator_request_log_mode CHECK (
        config_mode IS NULL
        OR config_mode IN ('ALWAYS_200', 'ALWAYS_500', 'FAIL_FIRST_N', 'PROCESSED_THEN_DELAY')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;