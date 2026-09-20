import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;

/**
 * Signs an RS256 access JWT with the project's own private key.
 *
 * Used to obtain a real, signature-validated identity for acceptance runs.
 * The token is still verified by the normal Spring Security chain, so this
 * does not bypass authentication.
 */
public class JwtTool {
    public static void main(String[] args) throws Exception {
        long userId = Long.parseLong(args[0]);
        String pemPath = args.length > 1 ? args[1]
                : "D:\\resume-project\\zhiguang_be\\src\\main\\resources\\keys\\private.pem";
        String pem = Files.readString(Path.of(pemPath));
        String b64 = pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                        .replaceAll("-----END [A-Z ]+-----", "")
                        .replaceAll("\\s", "");
        PrivateKey key = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
        long now = System.currentTimeMillis() / 1000;
        String header = "{\"alg\":\"RS256\",\"kid\":\"zhiguang-key\"}";
        String payload = "{\"iss\":\"zhiguang\",\"iat\":" + now + ",\"exp\":" + (now + 900)
                + ",\"sub\":\"" + userId + "\",\"token_type\":\"access\",\"uid\":" + userId + "}";
        String input = b64u(header.getBytes("UTF-8")) + "." + b64u(payload.getBytes("UTF-8"));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update(input.getBytes("UTF-8"));
        System.out.println(input + "." + b64u(sig.sign()));
    }
    static String b64u(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
