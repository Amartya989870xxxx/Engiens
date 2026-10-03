package com.engineeringlens.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RepoFileRepository extends JpaRepository<RepoFile, UUID> {

    /** One DELETE statement; a derived deleteBy… would load every row into memory first. */
    @Modifying
    @Query("delete from RepoFile f where f.repositoryId = :repositoryId")
    void deleteAllForRepository(UUID repositoryId);

    interface LabelCount {
        String getLabel();

        long getCount();
    }

    @Query("""
            select f.language as label, count(f) as count from RepoFile f
            where f.repositoryId = :repositoryId and f.ignored = false and f.language is not null
            group by f.language order by count(f) desc""")
    List<LabelCount> relevantLanguages(UUID repositoryId);

    @Query("""
            select f.ignoreReason as label, count(f) as count from RepoFile f
            where f.repositoryId = :repositoryId and f.ignored = true
            group by f.ignoreReason order by count(f) desc""")
    List<LabelCount> ignoreReasons(UUID repositoryId);
}
