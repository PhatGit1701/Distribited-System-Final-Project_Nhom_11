package vn.nhom11.bittorrent.tracker;
import jakarta.persistence.*;
@Entity @Table(name="user_accounts")
public class UserAccount {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false,unique=true,length=40) private String username;
 @Column(nullable=false,length=100) private String passwordHash;
 protected UserAccount() {}
 UserAccount(String username,String hash) { this.username=username; this.passwordHash=hash; }
 public Long getId(){return id;} public String getUsername(){return username;} public String getPasswordHash(){return passwordHash;}
}
