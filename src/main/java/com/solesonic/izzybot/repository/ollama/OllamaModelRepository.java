package com.solesonic.izzybot.repository.ollama;

import com.solesonic.izzybot.model.ollama.OllamaModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface OllamaModelRepository extends JpaRepository<OllamaModel, UUID> {

    @Query(value = """
                from OllamaModel om
                    where om.name = :name
            """)
    Optional<OllamaModel> findByName(@Param("name") String name);
}
