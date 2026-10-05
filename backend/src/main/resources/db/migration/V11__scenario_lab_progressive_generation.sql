-- Scenario Lab: larger labs (10/20) generate in parallel and can be worked on while still generating.
-- Additive only: labs created before this migration keep working (NULL stage = generation already over).

-- PLANNING while the scenario plan is being written, BUILDING while scenarios are built and validated, DONE after.
ALTER TABLE scenario_labs ADD COLUMN generation_stage VARCHAR(20);
-- Code scenarios that failed sandbox validation even after a repair and were replaced by a spare or a fallback.
ALTER TABLE scenario_labs ADD COLUMN scenarios_rejected INTEGER DEFAULT 0 NOT NULL;
-- Set when generation ended with fewer scenarios than requested, e.g. "17 of 20 scenarios generated."
ALTER TABLE scenario_labs ADD COLUMN generation_note VARCHAR(300);
