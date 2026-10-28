import local.agentos.deep.Contract;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.*;

/** Host test: strict parser, bounded capability validation and exact-byte signature encoding. No phone writes. */
public final class ContractTest {
    static int cases;
    static final String BASE = "{\"schema\":\"agentos.deepmod/1\",\"id\":\"proof\",\"package\":\"local.agentos.pigeon\",\"capabilityVersion\":1,\"baseRevision\":0}";
    static void reject(String raw) throws Exception {
        try { Contract.validate(raw); } catch (Exception expected) { cases++; return; }
        throw new AssertionError("accepted: " + raw.substring(0, Math.min(160, raw.length())));
    }
    static String add(String extra) { return BASE.substring(0, BASE.length()-1) + "," + extra + "}"; }
    public static void main(String[] args) throws Exception {
        Contract.validate(BASE); cases++;
        Contract.validate(add("\"theme\":{\"colors\":{\"bg\":\"#FF081A33\",\"accent\":\"#FFD6AD45\"},\"font\":{\"family\":\"serif\",\"scale\":1.1}},\"views\":[{\"screen\":\"chat-list\",\"match\":{\"text\":\"Demo promotion\"},\"visibility\":\"gone\"}],\"lists\":[{\"id\":\"pigeon_threads\",\"exclude\":{\"field\":\"promoted\",\"equals\":true}}]")); cases++;
        for (String raw : new String[]{
            BASE.replace("proof", "bad/id"), BASE.replace("pigeon", "beacon"), BASE.replace("/1", "/2"), BASE.replace("Revision\":0", "Revision\":-1"),
            BASE.replace("Revision\":0", "Revision\":0.5"), BASE.replace("Revision\":0", "Revision\":\"0\""), BASE.replace("\"proof\"", "123"),
            add("\"id\":\"twice\""), add("\"code\":\"alert(1)\""), BASE.replace("\"id\"", "id"), BASE.replace('"', '\''), BASE + " trailing",
            add("\"theme\":{\"colors\":{\"bg\":\"#00000000\"}}"), add("\"theme\":{\"colors\":{\"bg\":\"#081A33\"}}"),
            add("\"theme\":{\"colors\":{\"composer\":\"#FF081A33\"}}"), add("\"theme\":{\"colors\":{\"bg\":\"#FF081A33\",\"bg\":\"#FFD6AD45\"}}"),
            add("\"theme\":{\"font\":{\"family\":\"https://font\",\"scale\":1}}"), add("\"theme\":{\"font\":{\"family\":\"serif\",\"scale\":2}}"),
            add("\"theme\":{\"font\":{\"family\":\"serif\",\"scale\":\"1\"}}"), add("\"theme\":{\"font\":{\"family\":\"serif\",\"scale\":NaN}}"),
            add("\"views\":[{\"screen\":\"chat-list\",\"match\":{\"id\":\"send\"},\"visibility\":\"gone\"}]"),
            add("\"views\":[{\"screen\":\"chat-list\",\"match\":{\"id\":\"pigeon_overflow\",\"text\":\"Demo promotion\"},\"visibility\":\"gone\"}]"),
            add("\"lists\":[{\"id\":\"pigeon_threads\",\"exclude\":{\"field\":\"name\",\"equals\":\"Mom\"}}]"),
            add("\"buttons\":[{\"id\":\"send-all\",\"screen\":\"chat\",\"slot\":\"pigeon_chat_actions\",\"label\":\"Send\",\"action\":{\"id\":\"sms.send\",\"template\":\"send\",\"context\":\"contacts\"}}]"),
            add("\"theme\":{\"a\":[[[[[[[[[]]]]]]]]]}"), add("\"theme\":\"" + new String(new char[16384]).replace('\0', 'a') + "\"")
        }) reject(raw);
        JSONObject envelope = new JSONObject().put("payloadB64", "e30=").put("digest", Contract.hash("{}")).put("build", "build").put("cert", Contract.PIGEON_CERT)
            .put("revision", 1).put("task", "t1").put("mode", "preview").put("nonce", "n1").put("expires", 1).put("epoch", 1);
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC"); g.initialize(256); KeyPair k = g.generateKeyPair();
        Signature s = Signature.getInstance("SHA256withECDSA"); s.initSign(k.getPrivate()); s.update(Contract.signed(envelope)); byte[] signed = s.sign();
        s.initVerify(k.getPublic()); s.update(Contract.signed(envelope)); if (!s.verify(signed)) throw new AssertionError("signature"); cases++;
        for (String field : new String[]{"payloadB64", "digest", "build", "cert", "task", "mode", "nonce"}) {
            JSONObject altered = new JSONObject(envelope.toString()).put(field, "changed"); s.initVerify(k.getPublic()); s.update(Contract.signed(altered));
            if (s.verify(signed)) throw new AssertionError("tamper " + field); cases++;
        }
        for (String field : new String[]{"revision", "expires", "epoch"}) {
            JSONObject altered = new JSONObject(envelope.toString()).put(field, 2); s.initVerify(k.getPublic()); s.update(Contract.signed(altered));
            if (s.verify(signed)) throw new AssertionError("tamper " + field); cases++;
        }
        System.out.println(cases + " contract/signature cases PASS");
    }
}
