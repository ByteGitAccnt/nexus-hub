ALTER TABLE registered_services
    ADD CONSTRAINT ck_registered_services_base_path_not_nexushub
        CHECK (
            lower(btrim(base_path)) <> '/nexushub'
            AND lower(btrim(base_path)) NOT LIKE '/nexushub/%'
        );
