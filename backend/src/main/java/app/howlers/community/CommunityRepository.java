package app.howlers.community;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommunityRepository extends JpaRepository<Community, Long> {

    @EntityGraph(attributePaths = "topic")
    @Query("select c from Community c where lower(c.slug) = lower(:slug)")
    Optional<Community> findBySlug(@Param("slug") String slug);

    @Query("select count(c) > 0 from Community c where lower(c.slug) = lower(:slug)")
    boolean existsBySlug(@Param("slug") String slug);

    @EntityGraph(attributePaths = "topic")
    List<Community> findByIdIn(Collection<Long> ids);

    /** Directory listing; callers pass q lower-cased and "" for "no filter". Private communities are discoverable by name but their content is gated. */
    @EntityGraph(attributePaths = "topic")
    @Query("""
            select c from Community c left join c.topic t
            where c.status = 'ACTIVE' and (lower(c.name) like concat('%', :q, '%') or lower(c.slug) like concat('%', :q, '%'))
              and (:topic = '' or t.slug = :topic)""")
    Page<Community> directory(@Param("q") String q, @Param("topic") String topic, Pageable pageable);

    @Query(value = """
            SELECT c.id FROM communities c
            LEFT JOIN (SELECT community_id, count(*) AS n FROM community_members
                       WHERE joined_at > now() - interval '7 days' GROUP BY community_id) m ON m.community_id = c.id
            LEFT JOIN (SELECT community_id, count(*) AS n FROM posts
                       WHERE created_at > now() - interval '7 days' AND deleted_at IS NULL GROUP BY community_id) p ON p.community_id = c.id
            WHERE c.visibility <> 'PRIVATE' AND c.status = 'ACTIVE'
            ORDER BY coalesce(m.n, 0) * 2 + coalesce(p.n, 0) DESC, c.member_count DESC, c.id
            LIMIT :limit""", nativeQuery = true)
    List<Long> trendingIds(@Param("limit") int limit);

}
