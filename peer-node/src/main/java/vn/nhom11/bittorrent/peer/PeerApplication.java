package vn.nhom11.bittorrent.peer;
import vn.nhom11.bittorrent.peer.config.PeerProperties;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import vn.nhom11.bittorrent.Main;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(PeerProperties.class)
public class PeerApplication {
    public static void main(String[] args) {
        if (args.length > 0 && (args[0].equals("demo") || args[0].equals("download") || args[0].equals("--help"))) {
            Main.main(args);
        } else {
            SpringApplication.run(PeerApplication.class, args);
        }
    }
}
