import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.AlgorithmParameters;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.interfaces.ECKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Signs a VaultPass Desktop installer for the in-app updater (audit D10).
 *
 * <pre>
 * java tools\SignInstaller.java --keystore &lt;p12&gt; --installer &lt;exe&gt; --version &lt;x.y.z&gt; [--force] [--project-dir &lt;dir&gt;]
 * </pre>
 *
 * A standalone JDK 21 program, so the release key is only ever decrypted here and never inside Gradle or
 * a Gradle plugin. The keystore password is read from the console (hidden), never from an environment
 * variable or an argument. {@code --password-stdin} reads it from standard input instead; it exists for
 * the automated tests and is not for release use.
 *
 * <p>It writes {@code <installer>.sig} (base64 DER ECDSA P-256 over the message the app checks) and
 * {@code <installer>.sha256}, then verifies the signature by calling the app's own
 * {@code UpdateSignatureVerifier} from the built app image, with the key embedded in that image.
 *
 * <p>Exit codes: 0 signed and verified; 1 refused or failed; 2 the public key file was just created
 * (nothing was signed: rebuild so the app contains the key, then sign again).
 */
public final class SignInstaller {
    static final String KEY_ALIAS = "update";
    static final String PEM_RESOURCE = "update-signing-key.pem";
    static final String PEM_PATH = "src/main/resources/" + PEM_RESOURCE;
    static final String APP_IMAGE_JARS = "build/compose/binaries/main/app/VaultPass/app";
    static final String VERIFIER_CLASS = "com.vaultpass.desktop.data.update.UpdateSignatureVerifier";
    static final String MESSAGE_HEADER = "VaultPass-Desktop-Update-v1";
    static final Pattern VERSION = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+$");

    static final int EXIT_SIGNED = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_KEY_WRITTEN = 2;

    /** A refusal with a message for the person releasing. */
    static final class Refused extends Exception {
        Refused(String message) {
            super(message);
        }
    }

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (Refused e) {
            System.err.println("REFUSED: " + e.getMessage());
            code = EXIT_FAILED;
        } catch (Exception e) {
            System.err.println("FAILED: " + e);
            code = EXIT_FAILED;
        }
        System.exit(code);
    }

    static int run(String[] args) throws Exception {
        String keystoreArg = null;
        String installerArg = null;
        String version = null;
        String projectDirArg = null;
        boolean force = false;
        boolean passwordStdin = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--keystore" -> keystoreArg = value(args, ++i, "--keystore");
                case "--installer" -> installerArg = value(args, ++i, "--installer");
                case "--version" -> version = value(args, ++i, "--version");
                case "--project-dir" -> projectDirArg = value(args, ++i, "--project-dir");
                case "--force" -> force = true;
                case "--password-stdin" -> passwordStdin = true;
                default -> throw new Refused("Unknown argument " + args[i] + ". " + usage());
            }
        }
        if (keystoreArg == null || installerArg == null || version == null) throw new Refused(usage());
        if (!VERSION.matcher(version).matches()) {
            throw new Refused("--version must be major.minor.patch in digits, for example 1.0.2 (got \"" + version + "\"). "
                + "Pre-release and test builds are never signed with the release key.");
        }

        Path projectDir = Path.of(projectDirArg != null ? projectDirArg : System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path keystore = Path.of(keystoreArg).toAbsolutePath().normalize();
        Path installer = Path.of(installerArg).toAbsolutePath().normalize();
        String expectedName = "VaultPass-Setup-v" + version + ".exe";
        if (!Files.isRegularFile(keystore)) throw new Refused("Keystore not found: " + keystore);
        if (!Files.isRegularFile(installer)) throw new Refused("Installer not found: " + installer);
        if (!installer.getFileName().toString().equals(expectedName)) {
            throw new Refused("The installer must be named " + expectedName + " (got " + installer.getFileName() + "). "
                + "The signature covers the file name, and the app checks it against the release tag.");
        }
        Path signatureFile = installer.resolveSibling(expectedName + ".sig");
        Path checksumFile = installer.resolveSibling(expectedName + ".sha256");
        if (Files.exists(signatureFile) && !force) {
            throw new Refused(signatureFile + " already exists. Pass --force to replace it.");
        }

        PrivateKey privateKey;
        PublicKey publicKey;
        char[] password = passwordStdin ? readPasswordFromStdin() : readPasswordFromConsole(keystore);
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(keystore)) {
                store.load(in, password);
            }
            Key key = store.getKey(KEY_ALIAS, password);
            if (!(key instanceof PrivateKey)) throw new Refused("The keystore has no private key under the alias \"" + KEY_ALIAS + "\".");
            Certificate certificate = store.getCertificate(KEY_ALIAS);
            if (certificate == null) throw new Refused("The keystore has no certificate under the alias \"" + KEY_ALIAS + "\".");
            privateKey = (PrivateKey) key;
            publicKey = certificate.getPublicKey();
        } finally {
            Arrays.fill(password, '\0');
        }
        if (!(publicKey instanceof ECPublicKey ecPublic) || !isP256(ecPublic.getParams())
            || !(privateKey instanceof ECKey ecPrivate) || !isP256(ecPrivate.getParams())) {
            throw new Refused("The update signing key must be EC P-256 (keytool -keyalg EC -groupname secp256r1).");
        }

        byte[] spki = publicKey.getEncoded();
        String spkiSha256 = hex(MessageDigest.getInstance("SHA-256").digest(spki));
        Path pemFile = projectDir.resolve(PEM_PATH);
        if (!Files.exists(pemFile)) {
            Files.createDirectories(pemFile.getParent());
            Files.writeString(pemFile, toPem(spki), StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
            System.out.println("Wrote the update public key to " + pemFile);
            System.out.println("SHA-256 of the public key (SubjectPublicKeyInfo DER):");
            System.out.println("  " + spkiSha256);
            System.out.println("Record this value with your key backup; every later signing run prints it again.");
            System.out.println("Nothing was signed. Rebuild the installer so the app contains this key, then sign again.");
            return EXIT_KEY_WRITTEN;
        }

        byte[] pemBytes = Files.readAllBytes(pemFile);
        byte[] embeddedSpki = parsePem(new String(pemBytes, StandardCharsets.US_ASCII));
        if (!MessageDigest.isEqual(embeddedSpki, spki)) {
            throw new Refused("The keystore's key is not the update key in " + pemFile + ". Installed apps would reject this release.");
        }

        Path appJars = projectDir.resolve(APP_IMAGE_JARS);
        List<Path> jars = listJars(appJars);
        requireKeyInAppImage(jars, pemBytes, version, appJars);

        String sha256 = hex(sha256(installer));
        try (URLClassLoader appLoader = appImageLoader(jars)) {
            Class<?> verifierClass = Class.forName(VERIFIER_CLASS, true, appLoader);
            Object verifier = verifierClass.getField("INSTANCE").get(null);
            Method signedMessage = verifierClass.getMethod("signedMessage", String.class, String.class);
            Method loadKey = verifierClass.getMethod("loadEmbeddedPublicKey", ClassLoader.class);
            Method verify = verifierClass.getMethod("verify", PublicKey.class, String.class, String.class, String.class);

            // Sign the bytes the app itself builds, and refuse if they are not the documented v1 format.
            byte[] message = (byte[]) invoke(signedMessage, verifier, expectedName, sha256);
            byte[] documented = (MESSAGE_HEADER + "\n" + expectedName + "\n" + sha256).getBytes(StandardCharsets.UTF_8);
            if (!Arrays.equals(message, documented)) {
                throw new Refused("The app image builds a different signed message than " + MESSAGE_HEADER + ". Update this signer first.");
            }
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            signer.update(message);
            String signature = Base64.getEncoder().encodeToString(signer.sign());

            StandardOpenOption mode = force ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.CREATE_NEW;
            Files.writeString(signatureFile, signature + "\n", StandardCharsets.US_ASCII, StandardOpenOption.CREATE, StandardOpenOption.WRITE, mode);
            Files.writeString(checksumFile, sha256 + "  " + expectedName + "\n", StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

            // Check the written files exactly as an installed app will: its code, its embedded key.
            PublicKey appKey = (PublicKey) invoke(loadKey, verifier, appLoader);
            String writtenSignature = Files.readString(signatureFile, StandardCharsets.US_ASCII);
            String writtenSha256 = hex(sha256(installer));
            boolean accepted = appKey != null
                && MessageDigest.isEqual(appKey.getEncoded(), spki)
                && writtenSha256.equals(sha256)
                && (Boolean) invoke(verify, verifier, appKey, expectedName, writtenSha256, writtenSignature);
            String otherName = "VaultPass-Setup-v" + version + ".1.exe";
            boolean bindsName = !(Boolean) invoke(verify, verifier, appKey, otherName, writtenSha256, writtenSignature);
            if (!accepted || !bindsName) {
                Files.deleteIfExists(signatureFile);
                Files.deleteIfExists(checksumFile);
                throw new Refused("The new signature did not verify with the app image's UpdateSignatureVerifier. Nothing was signed.");
            }
        }

        System.out.println("Signed and verified " + expectedName + " with the update key embedded in the app image.");
        System.out.println("  Installer SHA-256:                " + sha256);
        System.out.println("  Public key SHA-256 (SPKI DER):    " + spkiSha256);
        System.out.println("Upload these three files to the GitHub release tagged v" + version + ":");
        System.out.println("  " + installer);
        System.out.println("  " + signatureFile);
        System.out.println("  " + checksumFile);
        return EXIT_SIGNED;
    }

    static String usage() {
        return "Usage: java tools\\SignInstaller.java --keystore <p12> --installer <VaultPass-Setup-v<version>.exe> --version <x.y.z> [--force] [--project-dir <dir>]";
    }

    static String value(String[] args, int index, String name) throws Refused {
        if (index >= args.length) throw new Refused(name + " needs a value. " + usage());
        return args[index];
    }

    static char[] readPasswordFromConsole(Path keystore) throws Refused {
        Console console = System.console();
        if (console == null) {
            throw new Refused("No interactive console to read the keystore password from. Run this in a terminal window "
                + "(build-installer.ps1 does), not with redirected input.");
        }
        char[] password = console.readPassword("Password for %s: ", keystore);
        if (password == null) throw new Refused("No password was entered.");
        return password;
    }

    /** Reads one line from standard input as characters. For tests only. */
    static char[] readPasswordFromStdin() throws IOException {
        Reader reader = new InputStreamReader(System.in, StandardCharsets.UTF_8);
        char[] buffer = new char[64];
        int length = 0;
        while (true) {
            int c = reader.read();
            if (c == -1 || c == '\n') break;
            if (length == buffer.length) {
                char[] bigger = Arrays.copyOf(buffer, buffer.length * 2);
                Arrays.fill(buffer, '\0');
                buffer = bigger;
            }
            buffer[length++] = (char) c;
        }
        if (length > 0 && buffer[length - 1] == '\r') length--;
        char[] password = Arrays.copyOf(buffer, length);
        Arrays.fill(buffer, '\0');
        return password;
    }

    static boolean isP256(ECParameterSpec params) throws Exception {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec expected = parameters.getParameterSpec(ECParameterSpec.class);
        return params.getCurve().equals(expected.getCurve())
            && params.getGenerator().equals(expected.getGenerator())
            && params.getOrder().equals(expected.getOrder())
            && params.getCofactor() == expected.getCofactor();
    }

    static List<Path> listJars(Path dir) throws IOException, Refused {
        if (!Files.isDirectory(dir)) {
            throw new Refused("No app image at " + dir + ". Build it first (gradlew createDistributable); build-installer.ps1 does.");
        }
        List<Path> jars = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".jar") && Files.isRegularFile(p)).sorted().forEach(jars::add);
        }
        if (jars.isEmpty()) throw new Refused("No .jar files in the app image at " + dir + ".");
        return jars;
    }

    /**
     * Requires the app image that the installer was built from to carry exactly the repo's public key and
     * this version, so an installer whose app has no key (and so could never verify later updates) is never signed.
     */
    static void requireKeyInAppImage(List<Path> jars, byte[] pemBytes, String version, Path dir) throws IOException, Refused {
        for (Path jar : jars) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                ZipEntry entry = zip.getEntry(PEM_RESOURCE);
                if (entry == null) continue;
                byte[] embedded;
                try (InputStream in = zip.getInputStream(entry)) {
                    embedded = in.readAllBytes();
                }
                if (!Arrays.equals(embedded, pemBytes)) {
                    throw new Refused(jar.getFileName() + " in the app image holds a different " + PEM_RESOURCE + " than " + PEM_PATH + ". Rebuild the app image.");
                }
                ZipEntry versionEntry = zip.getEntry("version.txt");
                String imageVersion = null;
                if (versionEntry != null) {
                    try (InputStream in = zip.getInputStream(versionEntry)) {
                        imageVersion = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                    }
                }
                if (!version.equals(imageVersion)) {
                    throw new Refused("The app image is version " + imageVersion + ", not " + version + ". Rebuild it after setting version.txt.");
                }
                return;
            }
        }
        throw new Refused("The app image in " + dir + " has no " + PEM_RESOURCE + ": the installer built from it could never verify "
            + "later updates. Rebuild the app image (and installer) now that " + PEM_PATH + " exists, then sign again.");
    }

    static URLClassLoader appImageLoader(List<Path> jars) throws IOException {
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < urls.length; i++) urls[i] = jars.get(i).toUri().toURL();
        return new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
    }

    static Object invoke(Method method, Object target, Object... args) throws Exception {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw e;
        }
    }

    static byte[] sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return digest.digest();
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    static String toPem(byte[] der) {
        return "-----BEGIN PUBLIC KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
            + "\n-----END PUBLIC KEY-----\n";
    }

    static byte[] parsePem(String pem) throws Exception {
        int begin = pem.indexOf("-----BEGIN PUBLIC KEY-----");
        int end = pem.indexOf("-----END PUBLIC KEY-----");
        if (begin < 0 || end <= begin) throw new Refused(PEM_PATH + " is not a PEM public key.");
        String body = pem.substring(begin + "-----BEGIN PUBLIC KEY-----".length(), end).replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(body);
        // Parse it to be sure it is a key, then compare the DER.
        KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
        return der;
    }
}
