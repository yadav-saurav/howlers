package app.howlers.community;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "community_rules")
public class CommunityRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id")
    private Community community;
    @Column(nullable = false)
    private int position;
    @Column(nullable = false)
    private String title;
    @Column(nullable = false)
    private String description = "";

    protected CommunityRule() {}

    public CommunityRule(Community community, int position, String title, String description) {
        this.community = community;
        this.position = position;
        this.title = title;
        this.description = description == null ? "" : description;
    }

    public Long getId() { return id; }
    public int getPosition() { return position; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
}
