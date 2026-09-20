import java.sql.*;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Acceptance DB helper.
 *
 * Usage:
 *   evidence <postId> <outFile>          -- dump latest agent_run + recent comments
 *   count    <triggerCommentId>          -- how many runs exist for that trigger
 *   dupcheck <triggerCommentId>          -- try inserting a duplicate run (idempotency probe)
 *   cleanup  <postId>                    -- delete this run's test rows
 *
 * Lives under scripts/acceptance/tools so it is version controlled: earlier
 * copies kept in a scratch folder were wiped by a `_*` cleanup and broke the
 * evidence step.
 */
public class AcceptanceDb {
    static final String URL = "jdbc:mysql://100.83.242.114:3306/zhiguang_auth?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    static final String USER = "root";
    static final String PASS = "czqCZQ197623@";

    public static void main(String[] args) throws Exception {
        String cmd = args[0];
        try (Connection c = DriverManager.getConnection(URL, USER, PASS)) {
            switch (cmd) {
                case "evidence" -> evidence(c, Long.parseLong(args[1]), args[2]);
                case "count" -> count(c, Long.parseLong(args[1]));
                case "dupcheck" -> dupcheck(c, Long.parseLong(args[1]));
                case "cleanup" -> cleanup(c, Long.parseLong(args[1]));
                case "mkpost" -> mkpost(args[1], args[2], args[3], args[4]);
                case "isolcheck" -> isolcheck(c, args[1], args[2], args[3], args[4], args[5]);
                default -> System.out.println("unknown command " + cmd);
            }
        }
    }

    static void evidence(Connection c, long postId, String out) throws Exception {
        StringBuilder sb = new StringBuilder("{\n  \"postId\": ").append(postId).append(",\n");
        sb.append("  \"agentRunLatest\": ");
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id,post_id,trigger_comment_id,actor_user_id,status,reply_comment_id,started_at,finished_at,error_message FROM agent_run WHERE post_id = " + postId + " ORDER BY id DESC LIMIT 1")) {
            if (rs.next()) {
                sb.append("{\"id\":").append(rs.getLong("id"))
                  .append(",\"postId\":").append(rs.getLong("post_id"))
                  .append(",\"triggerCommentId\":").append(rs.getLong("trigger_comment_id"))
                  .append(",\"actorUserId\":").append(rs.getLong("actor_user_id"))
                  .append(",\"status\":\"").append(rs.getString("status")).append("\"")
                  .append(",\"replyCommentId\":").append(rs.getObject("reply_comment_id"))
                  .append(",\"startedAt\":\"").append(rs.getTimestamp("started_at")).append("\"")
                  .append(",\"finishedAt\":\"").append(rs.getTimestamp("finished_at")).append("\"")
                  .append(",\"errorMessage\":").append(rs.getString("error_message")==null?"null":q(rs.getString("error_message")))
                  .append("}");
            } else sb.append("null");
        }
        sb.append(",\n  \"comments\": [");
        boolean first = true;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id,user_id,author_type,reply_comment_id,content,create_time FROM comments WHERE post_id=? ORDER BY create_time DESC LIMIT 6")) {
            ps.setLong(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("\n    {\"id\":").append(rs.getLong("id"))
                      .append(",\"userId\":").append(rs.getLong("user_id"))
                      .append(",\"authorType\":\"").append(rs.getString("author_type")).append("\"")
                      .append(",\"replyCommentId\":").append(rs.getObject("reply_comment_id"))
                      .append(",\"createTime\":\"").append(rs.getTimestamp("create_time")).append("\"")
                      .append(",\"content\":").append(q(rs.getString("content"))).append("}");
                }
            }
        }
        sb.append("\n  ]\n}");
        Files.write(Path.of(out), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + out);
    }

    static void count(Connection c, long triggerId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM agent_run WHERE trigger_comment_id = ?")) {
            ps.setLong(1, triggerId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); System.out.println(rs.getInt(1)); }
        }
    }

    /** Idempotency probe: a second run for the same trigger must be rejected. */
    static void dupcheck(Connection c, long triggerId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO agent_run (id,post_id,trigger_comment_id,actor_user_id,status) VALUES (?,?,?,?, 'PENDING')")) {
            ps.setLong(1, System.nanoTime() & 0x7fffffffffffffffL);
            ps.setLong(2, 0L);
            ps.setLong(3, triggerId);
            ps.setLong(4, 1L);
            try {
                int n = ps.executeUpdate();
                System.out.println("INSERTED rows=" + n);
            } catch (SQLException e) {
                System.out.println("REJECTED " + e.getErrorCode() + " " + e.getMessage());
            }
        }
    }

    static void cleanup(Connection c, long postId) throws Exception {
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM agent_run WHERE post_id = ?")) {
            ps.setLong(1, postId);
            System.out.println("agent_run deleted: " + ps.executeUpdate());
        }
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM comments WHERE post_id = ? AND (author_type = 'AGENT' OR user_id = 1)")) {
            ps.setLong(1, postId);
            System.out.println("comments deleted: " + ps.executeUpdate());
        }
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM agent_run WHERE post_id = 0")) {
            ps.executeUpdate();
        }
        c.commit();
        System.out.println("cleanup committed");
    }

    /**
     * Creates a DRAFT post whose body carries a nonce.
     * Drafts stay out of the public feed and are readable by get_post
     * (findDetailById does not filter status), so no publishing is needed.
     */
    static void mkpost(String title, String body, String jwt, String javaUrl) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

        HttpRequest r1 = HttpRequest.newBuilder()
                .uri(URI.create(javaUrl + "/api/v1/knowposts/drafts"))
                .header("Authorization", "Bearer " + jwt)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        String d = http.send(r1, HttpResponse.BodyHandlers.ofString()).body();
        String id = extract(d, "\"id\"\\s*:\\s*\"?([0-9]+)\"?");
        System.out.println("draft id=" + id);

        String pre = "{\"scene\":\"knowpost_content\",\"postId\":\"" + id + "\",\"contentType\":\"text/markdown\"}";
        HttpRequest r2 = HttpRequest.newBuilder()
                .uri(URI.create(javaUrl + "/api/v1/storage/presign"))
                .header("Authorization", "Bearer " + jwt)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(pre, StandardCharsets.UTF_8)).build();
        String p = http.send(r2, HttpResponse.BodyHandlers.ofString()).body();
        String objectKey = extract(p, "\"objectKey\"\\s*:\\s*\"([^\"]+)\"");
        String putUrl = extract(p, "\"putUrl\"\\s*:\\s*\"([^\"]+)\"");
        System.out.println("objectKey=" + objectKey);

        HttpRequest r3 = HttpRequest.newBuilder().uri(URI.create(putUrl))
                .header("Content-Type", "text/markdown")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bodyBytes)).build();
        HttpResponse<String> put = http.send(r3, HttpResponse.BodyHandlers.ofString());
        String etag = put.headers().firstValue("ETag").orElse("");
        System.out.println("PUT status=" + put.statusCode() + " etag=" + etag);

        String conf = "{\"objectKey\":" + q(objectKey) + ",\"etag\":" + q(etag)
                + ",\"size\":" + bodyBytes.length + ",\"sha256\":" + q(sha256(bodyBytes)) + "}";
        HttpRequest r4 = HttpRequest.newBuilder()
                .uri(URI.create(javaUrl + "/api/v1/knowposts/" + id + "/content/confirm"))
                .header("Authorization", "Bearer " + jwt)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(conf, StandardCharsets.UTF_8)).build();
        System.out.println("confirm status=" + http.send(r4, HttpResponse.BodyHandlers.ofString()).statusCode());

        HttpRequest r5 = HttpRequest.newBuilder()
                .uri(URI.create(javaUrl + "/api/v1/knowposts/" + id))
                .header("Authorization", "Bearer " + jwt)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"title\":" + q(title) + "}", StandardCharsets.UTF_8)).build();
        System.out.println("patch status=" + http.send(r5, HttpResponse.BodyHandlers.ofString()).statusCode());
        System.out.println("POSTID=" + id);
    }

    /**
     * Content-level isolation check: the Agent reply for A must contain A's
     * nonce and NOT B's, and vice versa. Nonces live only in the body (never
     * the title), so this proves the model read the BODY, not just metadata.
     */
    static void isolcheck(Connection c, String postA, String postB, String agentTok,
                          String javaUrl, String out) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String bodyA = getBody(http, javaUrl, agentTok, postA);
        String bodyB = getBody(http, javaUrl, agentTok, postB);
        String na = nonce(bodyA), nb = nonce(bodyB);
        String ra = latestAgentComment(c, postA), rb = latestAgentComment(c, postB);

        boolean aHasA = na != null && ra != null && ra.contains(na);
        boolean aNoB  = nb == null || ra == null || !ra.contains(nb);
        boolean bHasB = nb != null && rb != null && rb.contains(nb);
        boolean bNoA  = na == null || rb == null || !rb.contains(na);
        boolean ok = aHasA && aNoB && bHasB && bNoA;

        String json = "{\n  \"nonceA\": " + q(na) + ",\n  \"nonceB\": " + q(nb) + ","
                + "\n  \"replyA_contains_nonceA\": " + aHasA
                + ",\n  \"replyA_excludes_nonceB\": " + aNoB
                + ",\n  \"replyB_contains_nonceB\": " + bHasB
                + ",\n  \"replyB_excludes_nonceA\": " + bNoA
                + ",\n  \"overall\": \"" + (ok ? "PASS" : "FAIL") + "\"\n}";
        Files.write(Path.of(out), json.getBytes(StandardCharsets.UTF_8));
        System.out.println(json);
    }

    static String getBody(HttpClient http, String javaUrl, String agentTok, String postId) throws Exception {
        HttpRequest r = HttpRequest.newBuilder()
                .uri(URI.create(javaUrl + "/api/internal/agent/posts/" + postId))
                .header("X-Agent-Internal-Token", agentTok).GET().build();
        HttpResponse<String> resp = http.send(r, HttpResponse.BodyHandlers.ofString());
        return extract(resp.body(), "\"content\"\\s*:\\s*\"([^\"]*)\"");
    }

    static String nonce(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("ZG-NONCE-[A-Z]-[0-9a-f]{6,}").matcher(text);
        return m.find() ? m.group() : null;
    }

    static String latestAgentComment(Connection c, String postId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT content FROM comments WHERE post_id=? AND author_type='AGENT' ORDER BY create_time DESC LIMIT 1")) {
            ps.setLong(1, Long.parseLong(postId));
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString("content") : null; }
        }
    }

    static String extract(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    static String sha256(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Strict JSON string escaping: control characters must not appear raw. */
    static String q(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
                }
            }
        }
        return sb.append("\"").toString();
    }
}
