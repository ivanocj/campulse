package com.ivanocj.cameras.availability.presentation.swing;

import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import java.util.Optional;

/** Remembers the last submitted form between launches. */
public interface FormMemory {
    FormMemory NONE = new FormMemory() {
        @Override
        public Optional<FormState> load() {
            return Optional.empty();
        }

        @Override
        public void save(FormState state) {
        }
    };

    Optional<FormState> load();

    void save(FormState state);

    record FormState(String did, String profile, String servers, TransportProtocol protocol, int timeoutSeconds,
                     boolean lanSearch) {
    }
}
