package com.solesonic.repository.llm;

import com.solesonic.model.llm.LlmModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface LlmModelRepository extends JpaRepository<LlmModel, UUID> {

    @Query(value = """
                from LlmModel lm
                    where lm.name = :name
            """)
    Optional<LlmModel> findByName(@Param("name") String name);
}
