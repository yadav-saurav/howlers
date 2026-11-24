package app.howlers.storage;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, Long> {
    List<MediaAsset> findByIdInAndOwnerId(Collection<Long> ids, Long ownerId);
}
