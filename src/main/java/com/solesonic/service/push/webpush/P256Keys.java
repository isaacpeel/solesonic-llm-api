package com.solesonic.service.push.webpush;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.util.Arrays;

/**
 * P-256 keys in the raw form Web Push writes them: a public key is the 65-byte uncompressed point
 * ({@code 0x04 || X || Y}), a private key the 32-byte scalar. The JDK has a codec for neither.
 * <p>
 * A public key is checked to lie on the curve before it is used: every one arriving here comes from
 * a browser, and an off-curve point fed to ECDH is the classic invalid-curve attack.
 */
public final class P256Keys {
    public static final int PUBLIC_KEY_LENGTH = 65;
    public static final int PRIVATE_KEY_LENGTH = 32;

    private static final int COORDINATE_LENGTH = 32;
    private static final byte UNCOMPRESSED_POINT = 0x04;
    private static final String ALGORITHM = "EC";
    private static final String CURVE = "secp256r1";
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
    private static final byte[] PAIR_CHECK_CHALLENGE = "solesonic-vapid-pair-check".getBytes(StandardCharsets.US_ASCII);
    private static final ECParameterSpec PARAMETERS = parameters();

    private P256Keys() {
    }

    public static ECPublicKey publicKey(byte[] uncompressed) {
        if (uncompressed == null || uncompressed.length != PUBLIC_KEY_LENGTH || uncompressed[0] != UNCOMPRESSED_POINT) {
            throw new IllegalArgumentException("Not an uncompressed P-256 point");
        }

        BigInteger coordinateX = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 1 + COORDINATE_LENGTH));
        BigInteger coordinateY = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1 + COORDINATE_LENGTH, PUBLIC_KEY_LENGTH));

        if (!isOnCurve(coordinateX, coordinateY)) {
            throw new IllegalArgumentException("Not a point on P-256");
        }

        try {
            ECPublicKeySpec keySpec = new ECPublicKeySpec(new ECPoint(coordinateX, coordinateY), PARAMETERS);

            return (ECPublicKey) KeyFactory.getInstance(ALGORITHM).generatePublic(keySpec);
        } catch (GeneralSecurityException generalSecurityException) {
            throw new IllegalArgumentException("Not a P-256 public key", generalSecurityException);
        }
    }

    public static ECPrivateKey privateKey(byte[] scalar) {
        if (scalar == null || scalar.length != PRIVATE_KEY_LENGTH) {
            throw new IllegalArgumentException("Not a 32-byte P-256 scalar");
        }

        BigInteger value = new BigInteger(1, scalar);

        if (value.signum() == 0 || value.compareTo(PARAMETERS.getOrder()) >= 0) {
            throw new IllegalArgumentException("P-256 scalar out of range");
        }

        try {
            return (ECPrivateKey) KeyFactory.getInstance(ALGORITHM).generatePrivate(new ECPrivateKeySpec(value, PARAMETERS));
        } catch (GeneralSecurityException generalSecurityException) {
            throw new IllegalArgumentException("Not a P-256 private key");
        }
    }

    public static byte[] uncompressed(ECPublicKey publicKey) {
        byte[] encoded = new byte[PUBLIC_KEY_LENGTH];
        encoded[0] = UNCOMPRESSED_POINT;
        writeFixedLength(publicKey.getW().getAffineX(), encoded, 1);
        writeFixedLength(publicKey.getW().getAffineY(), encoded, 1 + COORDINATE_LENGTH);

        return encoded;
    }

    public static byte[] scalar(ECPrivateKey privateKey) {
        byte[] scalar = new byte[PRIVATE_KEY_LENGTH];
        writeFixedLength(privateKey.getS(), scalar, 0);

        return scalar;
    }

    public static KeyPair generate() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(ALGORITHM);
            keyPairGenerator.initialize(new ECGenParameterSpec(CURVE));

            return keyPairGenerator.generateKeyPair();
        } catch (GeneralSecurityException generalSecurityException) {
            throw new IllegalStateException("P-256 is unavailable", generalSecurityException);
        }
    }

    /**
     * The JDK cannot derive a public key from a private one, so a pair is proven the way VAPID uses
     * it: a signature made by one verifies under the other.
     */
    public static boolean isPair(ECPublicKey publicKey, ECPrivateKey privateKey) {
        try {
            Signature signer = Signature.getInstance(SIGNATURE_ALGORITHM);
            signer.initSign(privateKey);
            signer.update(PAIR_CHECK_CHALLENGE);
            byte[] signature = signer.sign();

            Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(PAIR_CHECK_CHALLENGE);

            return verifier.verify(signature);
        } catch (GeneralSecurityException generalSecurityException) {
            return false;
        }
    }

    private static boolean isOnCurve(BigInteger coordinateX, BigInteger coordinateY) {
        EllipticCurve curve = PARAMETERS.getCurve();
        BigInteger prime = ((ECFieldFp) curve.getField()).getP();

        if (coordinateX.compareTo(prime) >= 0 || coordinateY.compareTo(prime) >= 0) {
            return false;
        }

        BigInteger left = coordinateY.multiply(coordinateY).mod(prime);
        BigInteger right = coordinateX.pow(3)
                .add(curve.getA().multiply(coordinateX))
                .add(curve.getB())
                .mod(prime);

        return left.equals(right);
    }

    private static void writeFixedLength(BigInteger value, byte[] target, int offset) {
        byte[] bytes = value.toByteArray();
        int length = Math.min(bytes.length, COORDINATE_LENGTH);

        System.arraycopy(bytes, bytes.length - length, target, offset + COORDINATE_LENGTH - length, length);
    }

    private static ECParameterSpec parameters() {
        try {
            AlgorithmParameters algorithmParameters = AlgorithmParameters.getInstance(ALGORITHM);
            algorithmParameters.init(new ECGenParameterSpec(CURVE));

            return algorithmParameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException generalSecurityException) {
            throw new IllegalStateException("P-256 is unavailable", generalSecurityException);
        }
    }
}
