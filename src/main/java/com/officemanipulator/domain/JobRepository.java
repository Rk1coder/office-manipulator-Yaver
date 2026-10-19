package com.officemanipulator.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface JobRepository extends JpaRepository<Job, UUID> {

    @org.springframework.transaction.annotation.Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Job j SET j.status = :next WHERE j.id = :id AND j.status = :expected")
    int transition(UUID id, JobStatus expected, JobStatus next);

    @org.springframework.transaction.annotation.Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Job j SET j.status = com.officemanipulator.domain.JobStatus.CANCELLED WHERE j.id = :id AND j.status NOT IN ("
            + "com.officemanipulator.domain.JobStatus.DONE, com.officemanipulator.domain.JobStatus.FAILED, com.officemanipulator.domain.JobStatus.CANCELLED)")
    int cancelIfActive(UUID id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM Job j WHERE j.id = :id")
    java.util.Optional<Job> findForUpdate(UUID id);

    @org.springframework.transaction.annotation.Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Job j SET j.tokenInput = COALESCE(j.tokenInput,0) + :input, j.tokenOutput = COALESCE(j.tokenOutput,0) + :output, j.costUsd = COALESCE(j.costUsd,0) + :cost WHERE j.id = :id")
    void addUsage(UUID id, int input, int output, BigDecimal cost);

    @Query("SELECT COALESCE(SUM(j.costUsd), 0) FROM Job j WHERE j.costUsd IS NOT NULL")
    BigDecimal sumTotalCost();

    @Query("SELECT j FROM Job j WHERE j.expiresAt < :now AND j.filesCleanedAt IS NULL AND (j.status = com.officemanipulator.domain.JobStatus.DONE OR j.status = com.officemanipulator.domain.JobStatus.FAILED OR j.status = com.officemanipulator.domain.JobStatus.CANCELLED)")
    List<Job> findExpiredWithFile(OffsetDateTime now);

    @Modifying
    @Query("UPDATE Job j SET j.filePath = NULL, j.filesCleanedAt = CURRENT_TIMESTAMP WHERE j.id = :id")
    void clearFilePath(UUID id);

    @Query("SELECT j FROM Job j WHERE j.parentJobId IS NULL ORDER BY j.createdAt DESC LIMIT 50")
    List<Job> findRecent();

    List<Job> findByParentJobId(UUID parentJobId);
}
