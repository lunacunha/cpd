# README

This README provides instructions on how to compile and run the **Server** and **Client** for the TLS-based Java chat system.

---

## 1. Prerequisites

* **Java JDK 23** (or higher) installed.
* `keytool` (included with the JDK).
* External JSON library: `json-20250517.jar` located in `src/lib/`.
* Output directory for compiled classes: `out/production/assign2`.

> **Note:** If you need to run on an older Java runtime (e.g., Java 21), recompile the code with `--release 21` or set `sourceCompatibility` and `targetCompatibility` in your build tool accordingly.

---

## 2. Generate the Keystore (Server)

1. In the project root, create the keystore file `server.jks`:

   ```bash
   keytool -genkeypair \
     -alias server \
     -keyalg RSA \
     -keysize 2048 \
     -validity 365 \
     -keystore server.jks \
     -storepass <keystore-password> \
     -keypass <keystore-password> \
     -dname "CN=localhost, OU=MyOrg, O=MyCompany, L=Lisbon, ST=Lisbon, C=PT"
   ```

2. Confirm that `server.jks` exists in the project directory.

---

## 3. Generate the Truststore (Client)

1. Export the server certificate from `server.jks`:

   ```bash
   keytool -exportcert \
     -alias server \
     -keystore server.jks \
     -storepass <keystore-password> \
     -file server.crt
   ```

2. Create the client truststore `truststore.jks` by importing the server certificate:

   ```bash
   keytool -importcert \
     -alias server \
     -file server.crt \
     -keystore truststore.jks \
     -storepass <truststore-password> \
     -noprompt
   ```

3. Confirm that `truststore.jks` exists in the project directory.

---

## 4. Compile the Code

From the project root, run:

```bash
javac --release 23 \
  -d out/production/assign2 \
  src/**/*.java
```

> For compatibility with Java 21 or lower, replace `--release 23` with `--release 21`.

---

## 5. Run the Server

### On macOS/Linux with JDK 23:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 23)
$JAVA_HOME/bin/java \
  -Djavax.net.ssl.keyStore=server.jks \
  -Djavax.net.ssl.keyStorePassword=<keystore-password> \
  -classpath out/production/assign2:src/lib/json-20250517.jar \
  Server
```

### Or invoke the JDK directly:

```bash
/Users/luna/Library/Java/JavaVirtualMachines/openjdk-23.0.2/Contents/Home/bin/java \
  -Djavax.net.ssl.keyStore=server.jks \
  -Djavax.net.ssl.keyStorePassword=<keystore-password> \
  -classpath out/production/assign2:src/lib/json-20250517.jar \
  Server
```

### On Windows (adjust paths as needed):

```bat
set JAVA_HOME=C:\Path\to\jdk-23
"%JAVA_HOME%\bin\java" ^
  -Djavax.net.ssl.keyStore=server.jks ^
  -Djavax.net.ssl.keyStorePassword=<keystore-password> ^
  -classpath out\production\assign2;src\lib\json-20250517.jar ^
  Server
```

---

## 6. Run the Client

### On macOS/Linux with JDK 23:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 23)
$JAVA_HOME/bin/java \
  -Djavax.net.ssl.trustStore=truststore.jks \
  -Djavax.net.ssl.trustStorePassword=<truststore-password> \
  -classpath out/production/assign2:src/lib/json-20250517.jar \
  Client
```

### Or invoke the JDK directly:

```bash
/Users/luna/Library/Java/JavaVirtualMachines/openjdk-23.0.2/Contents/Home/bin/java \
  -Djavax.net.ssl.trustStore=truststore.jks \
  -Djavax.net.ssl.trustStorePassword=<truststore-password> \
  -classpath out/production/assign2:src/lib/json-20250517.jar \
  Client
```

### On Windows:

```bat
set JAVA_HOME=C:\Path\to\jdk-23
"%JAVA_HOME%\bin\java" ^
  -Djavax.net.ssl.trustStore=truststore.jks ^
  -Djavax.net.ssl.trustStorePassword=<truststore-password> ^
  -classpath out\production\assign2;src\lib\json-20250517.jar ^
  Client
```

---

## 7. Useful Client Commands

* `/join <room>`: Join or create a chat room.
* `/join AI:<name>|<prompt>`: Join or create a room with an AI bot.
* `/leave`: Leave the current room.
* `/rooms`: List all available rooms.
* `/quit`: Exit the client.
* `/help`: Show this help message.

---

*Enjoy secure TLS chat with multi-user support and integrated AI!*
