package app.howlers.topic;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TopicRepository extends JpaRepository<Topic, Long> {
    List<Topic> findAllByOrderByNameAsc();

    Optional<Topic> findBySlug(String slug);

    @Modifying
    @Query(value = "DELETE FROM user_topics WHERE user_id = :userId", nativeQuery = true)
    void clearUserTopics(@Param("userId") long userId);

    @Modifying
    @Query(value = "INSERT INTO user_topics (user_id, topic_id) VALUES (:userId, :topicId) ON CONFLICT DO NOTHING", nativeQuery = true)
    void addUserTopic(@Param("userId") long userId, @Param("topicId") long topicId);
}
