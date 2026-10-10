-- The task constraints, added without blocking and validated afterwards.

ALTER TABLE tasks
    ADD CONSTRAINT tasks_project_id_fkey FOREIGN KEY (project_id)
        REFERENCES projects (id) ON DELETE CASCADE NOT VALID;

ALTER TABLE tasks VALIDATE CONSTRAINT tasks_project_id_fkey;

ALTER TABLE tasks ADD CHECK (priority BETWEEN 1 AND 5);
