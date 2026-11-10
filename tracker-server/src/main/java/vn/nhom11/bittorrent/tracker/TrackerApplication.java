package vn.nhom11.bittorrent.tracker;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
@SpringBootApplication @EnableScheduling
public class TrackerApplication {
 public static void main(String[] args) { SpringApplication.run(TrackerApplication.class,args); }
}
