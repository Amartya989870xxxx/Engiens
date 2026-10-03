-- Asked only of working professionals. Existing professionals are asked on their next profile edit.
ALTER TABLE user_profiles ADD COLUMN work_experience VARCHAR(30);
