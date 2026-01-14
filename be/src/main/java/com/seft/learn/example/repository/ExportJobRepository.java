package com.seft.learn.example.repository;

import com.seft.learn.example.entity.ExportJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ExportJobRepository extends JpaRepository<ExportJob, UUID> {

    @Modifying
    @Query("UPDATE ExportJob e SET e.processedRecords = :processed WHERE e.id = :id")
    void updateProgress(@Param("id") UUID id, @Param("processed") Long processed);
}
