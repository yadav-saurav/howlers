package app.howlers.topic;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "topics")
public class Topic {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String slug;
    private String name;
    private String description;

    protected Topic() {}

    public Topic(String slug, String name, String description) {
        this.slug = slug;
        this.name = name;
        this.description = description;
    }

    public Long getId() { return id; }
    public String getSlug() { return slug; }
    public String getName() { return name; }
    public String getDescription() { return description; }

    public record TopicDto(long id, String slug, String name, String description) {
        public static TopicDto of(Topic t) {
            return t == null ? null : new TopicDto(t.getId(), t.getSlug(), t.getName(), t.getDescription());
        }
    }
}
