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
@Table(name = "community_flairs")
public class CommunityFlair {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id")
    private Community community;
    @Column(nullable = false)
    private String text;
    @Column(nullable = false)
    private String color;

    protected CommunityFlair() {}

    public CommunityFlair(Community community, String text, String color) {
        this.community = community;
        this.text = text;
        this.color = color;
    }

    public Long getId() { return id; }
    public Community getCommunity() { return community; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
}
