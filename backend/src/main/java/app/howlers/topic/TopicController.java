package app.howlers.topic;

import app.howlers.topic.Topic.TopicDto;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Topics")
@RestController
@RequestMapping("/api/v1/topics")
public class TopicController {
    private final TopicRepository topics;

    public TopicController(TopicRepository topics) {
        this.topics = topics;
    }

    @GetMapping
    public List<TopicDto> list() {
        return topics.findAllByOrderByNameAsc().stream().map(TopicDto::of).toList();
    }
}
