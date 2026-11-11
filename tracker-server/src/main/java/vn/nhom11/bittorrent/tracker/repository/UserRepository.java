package vn.nhom11.bittorrent.tracker.repository;
import vn.nhom11.bittorrent.tracker.model.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface UserRepository extends JpaRepository<UserAccount,Long> {
 Optional<UserAccount> findByUsername(String username);
}
