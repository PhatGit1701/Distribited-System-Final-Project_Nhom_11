package vn.nhom11.bittorrent.tracker.model;
import jakarta.persistence.*;
@Entity @Table(name="users")
public class UserAccount {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false,unique=true,length=40) private String username;
 @Column(nullable=false,length=100) private String passwordHash;
 @Column(nullable=false,unique=true,length=64) private String passkey;
 @Column(nullable=false,length=20) private String role="USER";
 @Column(name="is_banned",nullable=false) private boolean banned;
 @Version private Long version;
 protected UserAccount() {}
 public UserAccount(String username,String hash) {
   this.username=username; this.passwordHash=hash;
   byte[] random=new byte[32]; new java.security.SecureRandom().nextBytes(random);
   this.passkey=java.util.HexFormat.of().formatHex(random);
 }
 public Long getId(){return id;} public String getUsername(){return username;} public String getPasswordHash(){return passwordHash;}
}
