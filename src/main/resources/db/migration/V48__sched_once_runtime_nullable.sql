-- ONCE 类型的定时任务只有 once_at，没有周期性 run_time；放开非空约束。
ALTER TABLE agent_scheduled_task ALTER COLUMN run_time DROP NOT NULL;
