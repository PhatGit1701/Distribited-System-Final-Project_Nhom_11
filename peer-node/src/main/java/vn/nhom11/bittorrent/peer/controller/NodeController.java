package vn.nhom11.bittorrent.peer.controller;
import vn.nhom11.bittorrent.peer.dto.SessionInfo;
import vn.nhom11.bittorrent.peer.dto.PeerView;
import vn.nhom11.bittorrent.peer.dto.NodeInfo;
import vn.nhom11.bittorrent.peer.dto.Login;
import vn.nhom11.bittorrent.peer.dto.DownloadRequest;
import vn.nhom11.bittorrent.peer.event.NodeEventHub;
import vn.nhom11.bittorrent.peer.security.NodeSessionFilter;
import vn.nhom11.bittorrent.peer.service.NodeService;

import jakarta.servlet.http.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.*;
import vn.nhom11.bittorrent.shared.*;
import java.io.*;
import java.util.*;

@RestController
@RequestMapping("/api/node")
public class NodeController {
    private final NodeService node;
    public NodeController(NodeService node) { this.node=node; }
    @GetMapping("/status")
    public NodeInfo status(HttpServletRequest request) {
        return node.status(request.getServerPort());
    }
    @PostMapping(value = "/session", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SessionInfo login(@RequestBody Login login, HttpServletRequest request) throws IOException {
        node.login(login);
        HttpSession old = request.getSession(false); if (old != null) old.invalidate();
        HttpSession session = request.getSession(true);
        session.setAttribute(NodeSessionFilter.USER, login.username());
        session.setAttribute(NodeSessionFilter.TOKEN, UUID.randomUUID().toString());
        return session(request);
    }
    @GetMapping("/session")
    public SessionInfo session(HttpServletRequest request) {
        return new SessionInfo(status(request), (String) request.getSession().getAttribute(NodeSessionFilter.TOKEN));
    }
    @DeleteMapping("/session")
    public void logout(HttpServletRequest request) { request.getSession().invalidate(); }

    @GetMapping("/snapshot")
    public NodeEventHub.Snapshot snapshot(HttpServletRequest request) throws IOException {
        return node.snapshot(request.getServerPort());
    }
    @GetMapping("/torrents")
    public List<TorrentMetadata> torrents() throws IOException {
        return node.torrents();
    }
    @GetMapping("/swarm/{fileId}")
    public List<PeerView> swarm(@PathVariable String fileId) throws IOException {
        return node.swarm(fileId);
    }
    @PostMapping(value = "/publish", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public TorrentMetadata publish(@RequestPart("file") MultipartFile file) throws IOException {
        try (InputStream content=file.getInputStream()) { return node.publish(file.getOriginalFilename(), content); }
    }
    @PostMapping("/downloads")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public NodeEventHub.DownloadView download(@RequestBody DownloadRequest request) throws IOException {
        return node.download(request.fileId());
    }
    @DeleteMapping("/downloads/{fileId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void cancel(@PathVariable String fileId) {
        node.cancel(fileId);
    }
    @GetMapping("/files/{fileId}")
    public ResponseEntity<Resource> file(@PathVariable String fileId) {
        var file = node.file(fileId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.metadata().fileName(),
                        java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .contentLength(file.metadata().fileSize()).body(new FileSystemResource(file.path()));
    }
}
