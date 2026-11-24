package app.howlers.collection;

import app.howlers.collection.CollectionDtos.AddItem;
import app.howlers.collection.CollectionDtos.CollectionDto;
import app.howlers.collection.CollectionDtos.CreateCollection;
import app.howlers.collection.CollectionDtos.ItemDto;
import app.howlers.collection.CollectionDtos.Membership;
import app.howlers.collection.CollectionDtos.Reorder;
import app.howlers.collection.CollectionDtos.SharedCollection;
import app.howlers.collection.CollectionDtos.TagCount;
import app.howlers.collection.CollectionDtos.UpdateCollection;
import app.howlers.collection.CollectionDtos.UpdateItem;
import app.howlers.common.security.AuthUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Smart Collections")
@RestController
@RequestMapping("/api/v1")
public class CollectionController {
    private final CollectionService service;

    public CollectionController(CollectionService service) {
        this.service = service;
    }

    @GetMapping("/me/collections")
    public List<CollectionDto> mine(@AuthenticationPrincipal AuthUser user) {
        return service.mine(user);
    }

    @PostMapping("/me/collections")
    @ResponseStatus(HttpStatus.CREATED)
    public CollectionDto create(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody CreateCollection req) {
        return service.create(user, req.name(), req.description());
    }

    @PatchMapping("/me/collections/{id}")
    public CollectionDto update(@AuthenticationPrincipal AuthUser user, @PathVariable long id, @Valid @RequestBody UpdateCollection req) {
        return service.update(user, id, req);
    }

    @DeleteMapping("/me/collections/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser user, @PathVariable long id) {
        service.delete(user, id);
    }

    @GetMapping("/me/collections/{id}/items")
    public List<ItemDto> items(@AuthenticationPrincipal AuthUser user, @PathVariable long id,
                               @RequestParam(required = false) String q, @RequestParam(required = false) String tag,
                               @RequestParam(required = false) String type) {
        return service.items(user, id, q, tag, type);
    }

    @PostMapping("/me/collections/{id}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemDto add(@AuthenticationPrincipal AuthUser user, @PathVariable long id, @Valid @RequestBody AddItem req) {
        return service.add(user, id, req);
    }

    @PutMapping("/me/collections/{id}/order")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reorder(@AuthenticationPrincipal AuthUser user, @PathVariable long id, @Valid @RequestBody Reorder req) {
        service.reorder(user, id, req.itemIds());
    }

    @GetMapping("/me/library/items")
    public List<ItemDto> library(@AuthenticationPrincipal AuthUser user, @RequestParam(required = false) String q,
                                 @RequestParam(required = false) String tag, @RequestParam(required = false) String type) {
        return service.items(user, null, q, tag, type);
    }

    @GetMapping("/me/library/tags")
    public List<TagCount> tags(@AuthenticationPrincipal AuthUser user) {
        return service.tags(user);
    }

    @GetMapping("/me/library/membership")
    public List<Membership> membership(@AuthenticationPrincipal AuthUser user, @RequestParam long postId,
                                       @RequestParam(required = false) Long commentId) {
        return service.membership(user, postId, commentId);
    }

    @PatchMapping("/me/collection-items/{itemId}")
    public ItemDto updateItem(@AuthenticationPrincipal AuthUser user, @PathVariable long itemId, @Valid @RequestBody UpdateItem req) {
        return service.updateItem(user, itemId, req);
    }

    @DeleteMapping("/me/collection-items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeItem(@AuthenticationPrincipal AuthUser user, @PathVariable long itemId) {
        service.removeItem(user, itemId);
    }

    @GetMapping("/collections/shared/{token}")
    public SharedCollection shared(@PathVariable String token, @AuthenticationPrincipal AuthUser user) {
        return service.shared(token, user);
    }
}
