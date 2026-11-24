package app.howlers.user;

import app.howlers.user.dto.UserDtos.UserSummary;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Batch lookups of user summaries for list views (one query per page, never one per row). */
@Component
public class UserDirectory {
    private final UserRepository users;

    public UserDirectory(UserRepository users) {
        this.users = users;
    }

    public Map<Long, UserSummary> summaries(Collection<Long> ids) {
        var distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        Map<Long, UserSummary> out = new HashMap<>();
        if (distinct.isEmpty()) return out;
        for (User u : users.findByIdIn(distinct)) {
            UserSummary s = UserMapper.toSummary(u);
            if (s != null) out.put(u.getId(), s);
        }
        return out;
    }
}
