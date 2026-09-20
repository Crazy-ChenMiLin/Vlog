import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Base64;

/**
 * Acceptance cleanup helper: removes the fourth category of test residue.
 *
 *   ossdel  <objectKey>   delete the OSS/MinIO body object
 *   delpost <postId>      delete the know_posts row
 *   escheck <postId>      report whether ES still holds a doc for this id
 *
 * Uses io.minio.MinioClient (what the project actually depends on). Compile and
 * run with the project classpath:
 *   mvn -o -q dependency:build-classpath -Dmdep.outputFile=cp.txt
 */
public class OssClean {
    static final String EP = "http://100.83.242.114:9000";
    static final String AK = "minio_fjTXH3";
    static final String SK = "czqCZQ197623";
    static final String BK = "zhiguang";
    static final String DB = "jdbc:mysql://100.83.242.114:3306/zhiguang_auth?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    static final String ES = "http://100.83.242.114:9200";

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "ossdel" -> ossdel(args[1]);
            case "delpost" -> delpost(Long.parseLong(args[1]));
            case "escheck" -> escheck(args[1]);
            default -> System.out.println("unknown command " + args[0]);
        }
    }

    static MinioClient mc() {
        return MinioClient.builder().endpoint(EP).credentials(AK, SK).build();
    }

    static void ossdel(String key) throws Exception {
        mc().removeObject(RemoveObjectArgs.builder().bucket(BK).object(key).build());
        System.out.println("deleted oss object: " + key);
    }

    static void delpost(long id) throws Exception {
        try (Connection c = DriverManager.getConnection(DB, "root", "czqCZQ197623@");
             PreparedStatement ps = c.prepareStatement("DELETE FROM know_posts WHERE id = ?")) {
            ps.setLong(1, id);
            System.out.println("know_posts rows deleted: " + ps.executeUpdate());
        }
    }

    /** Drafts were never published, so ES should not contain them — verify. */
    static void escheck(String id) throws Exception {
        HttpClient h = HttpClient.newHttpClient();
        String auth = Base64.getEncoder().encodeToString(("elastic:czqCZQ197623@").getBytes());
        for (String idx : new String[] { "zhiguang-ai-index" }) {
            HttpRequest r = HttpRequest.newBuilder()
                    .uri(URI.create(ES + "/" + idx + "/_doc/" + id))
                    .header("Authorization", "Basic " + auth).GET().build();
            HttpResponse<String> resp = h.send(r, HttpResponse.BodyHandlers.ofString());
            System.out.println(idx + " -> HTTP " + resp.statusCode()
                    + (resp.statusCode() == 200 ? "  FOUND (must clean)" : "  not indexed"));
        }
    }
}
