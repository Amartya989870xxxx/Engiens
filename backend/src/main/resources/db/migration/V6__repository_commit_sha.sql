-- The exact commit an import captured, so analysis reads files from the same snapshot the inventory
-- describes. Repositories imported before this column existed have NULL until they are re-imported;
-- analysis then pins the branch head at analysis time and records it.
ALTER TABLE repositories ADD COLUMN commit_sha VARCHAR(40);
