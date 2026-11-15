// Generates signatures.json, the test vectors for src/crypto/signatures.ts.
//
// The vectors come from the same code the phone runs: libsignal for the
// identity key, and the JDK's SHA256withECDSA (the format Android Keystore
// emits) for the device key.
//
//   java -cp libsignal-client-<version>.jar GenFixtures.java > signatures.json

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.signal.libsignal.protocol.ecc.ECKeyPair;

public class GenFixtures {
  static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }

  public static void main(String[] args) throws Exception {
    SecureRandom random = new SecureRandom();
    StringBuilder out = new StringBuilder("{\n  \"identity\": [\n");
    int count = 16;
    for (int i = 0; i < count; i++) {
      ECKeyPair pair = ECKeyPair.generate();
      byte[] message = new byte[i * 7]; // includes the empty message
      random.nextBytes(message);
      byte[] signature = pair.getPrivateKey().calculateSignature(message);
      out.append(String.format("    {\"publicKey\": \"%s\", \"message\": \"%s\", \"signature\": \"%s\"}%s\n",
          b64(pair.getPublicKey().getPublicKeyBytes()), b64(message), b64(signature), i < count - 1 ? "," : ""));
    }
    out.append("  ],\n  \"device\": [\n");
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    for (int i = 0; i < count; i++) {
      KeyPair pair = generator.generateKeyPair();
      byte[] message = new byte[i * 7];
      random.nextBytes(message);
      Signature signer = Signature.getInstance("SHA256withECDSA");
      signer.initSign(pair.getPrivate());
      signer.update(message);
      out.append(String.format("    {\"spki\": \"%s\", \"message\": \"%s\", \"signature\": \"%s\"}%s\n",
          b64(pair.getPublic().getEncoded()), b64(message), b64(signer.sign()), i < count - 1 ? "," : ""));
    }
    out.append("  ]\n}\n");
    System.out.print(out);
  }
}
