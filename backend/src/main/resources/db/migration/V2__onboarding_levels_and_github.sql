-- Levels now describe academic/professional status instead of seniority.
UPDATE user_profiles SET level = 'UNDERGRADUATE' WHERE level = 'STUDENT';
UPDATE user_profiles SET level = 'PROFESSIONAL' WHERE level IN ('JUNIOR', 'MID', 'SENIOR');
UPDATE user_profiles SET class_year = NULL WHERE level = 'PROFESSIONAL' OR class_year > 4;

-- Previous projects are now read from the user's GitHub profile instead of free text.
ALTER TABLE user_profiles ADD COLUMN github_username VARCHAR(39);
ALTER TABLE user_profiles DROP COLUMN previous_projects;

-- Tag lists are picked from dropdowns now; 20 tags x 40 chars no longer fit in 500.
ALTER TABLE user_profiles ALTER COLUMN languages SET DATA TYPE VARCHAR(1000);
ALTER TABLE user_profiles ALTER COLUMN frameworks SET DATA TYPE VARCHAR(1000);
ALTER TABLE user_profiles ALTER COLUMN databases SET DATA TYPE VARCHAR(1000);
ALTER TABLE user_profiles ALTER COLUMN experience_areas SET DATA TYPE VARCHAR(1000);
