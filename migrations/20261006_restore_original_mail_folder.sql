-- Apply once to an existing Mail System database before starting the updated server.
-- Existing TRASH entries keep NULL and restore using the recipient-role fallback.
ALTER TABLE mail_recipient
    ADD COLUMN original_folder_id INT NULL AFTER folder_id,
    ADD CONSTRAINT fk_mail_recipient_original_folder
        FOREIGN KEY (original_folder_id) REFERENCES folder(folder_id) ON DELETE SET NULL;
