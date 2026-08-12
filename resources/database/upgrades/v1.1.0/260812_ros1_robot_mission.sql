-- 260812 ROS1 搬运 Demo：已批准候选任务的结构化机器人任务与网关反馈。

CREATE TABLE IF NOT EXISTS t_iron_ore_robot_mission (
    id               VARCHAR(20)  NOT NULL PRIMARY KEY,
    task_template_id VARCHAR(20)  NOT NULL,
    owner_user_id    VARCHAR(20)  NOT NULL,
    robot_id          VARCHAR(64)  NOT NULL,
    status            VARCHAR(32)  NOT NULL,
    plan_hash         VARCHAR(64)  NOT NULL,
    mission_data      JSONB        NOT NULL,
    gateway_state     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    current_step      INTEGER      NOT NULL DEFAULT 0,
    total_steps       INTEGER      NOT NULL DEFAULT 0,
    current_skill_id  VARCHAR(64),
    status_message    VARCHAR(512),
    dispatched_at     TIMESTAMP,
    completed_at      TIMESTAMP,
    created_by        VARCHAR(64),
    updated_by        VARCHAR(64),
    create_time       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted           SMALLINT     NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_iron_ore_robot_mission_task
    ON t_iron_ore_robot_mission (task_template_id)
    WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_iron_ore_robot_mission_owner
    ON t_iron_ore_robot_mission (owner_user_id, create_time DESC);
COMMENT ON TABLE t_iron_ore_robot_mission IS '已批准候选任务编译出的 ROS1 机器人任务及反馈快照';
