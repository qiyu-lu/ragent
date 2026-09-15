-- Procedure-driven task agent: local sample registration and station booking.
CREATE TABLE IF NOT EXISTS t_task_agent_run (
    id VARCHAR(64) PRIMARY KEY,
    owner_user_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    state_json TEXT NOT NULL,
    lease_token VARCHAR(64),
    lease_until BIGINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_task_agent_owner ON t_task_agent_run(owner_user_id, updated_at);

CREATE TABLE IF NOT EXISTS t_task_agent_event (
    run_id VARCHAR(64) NOT NULL REFERENCES t_task_agent_run(id),
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    message TEXT NOT NULL,
    detail_json TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (run_id, sequence_no)
);

CREATE TABLE IF NOT EXISTS t_task_agent_sample (
    owner_user_id VARCHAR(64) NOT NULL,
    id VARCHAR(64) NOT NULL,
    name VARCHAR(200) NOT NULL,
    test_type VARCHAR(64) NOT NULL,
    label_verified BOOLEAN NOT NULL,
    handoff_ready BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'REGISTERED',
    PRIMARY KEY (owner_user_id, id)
);

CREATE TABLE IF NOT EXISTS t_task_agent_station (
    owner_user_id VARCHAR(64) NOT NULL,
    id VARCHAR(64) NOT NULL,
    name VARCHAR(200) NOT NULL,
    test_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'AVAILABLE',
    reservation_run_id VARCHAR(64),
    PRIMARY KEY (owner_user_id, id)
);

CREATE TABLE IF NOT EXISTS t_task_agent_submission (
    run_id VARCHAR(64) PRIMARY KEY REFERENCES t_task_agent_run(id),
    owner_user_id VARCHAR(64) NOT NULL,
    sample_id VARCHAR(64) NOT NULL,
    station_id VARCHAR(64) NOT NULL,
    document_id VARCHAR(64) NOT NULL,
    document_version VARCHAR(128) NOT NULL,
    proposal_json TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE (owner_user_id, sample_id)
);
